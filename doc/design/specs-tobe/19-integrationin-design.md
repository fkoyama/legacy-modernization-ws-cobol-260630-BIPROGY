# サブシステム設計書（TO-BE） — 19-integrationin

> COBOL `INTI-DECODE-BATCH` を Java の **`DecodeStage`** へ移行する TO-BE 設計。
> AS-IS 一次情報: [specs-asis/02-transaction-pipeline.md § 19](../specs-asis/02-transaction-pipeline.md) / [src/inti-decode-batch.cob](../../../subsystems/19-integrationin/src/inti-decode-batch.cob)
> 横断方針: [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `19-integrationin` |
| ディレクトリ | [subsystems/19-integrationin/](../../../subsystems/19-integrationin/) |
| 分類 | トランザクション処理系（連携入力 / パイプライン入口） |
| API契約 | [copy/api/inti-api.cpy](../../../subsystems/19-integrationin/copy/api/inti-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

上流バンクから受領した **EBCDIC 固定長 800 バイトの取引ファイル**を ASCII/UTF-8 へデコードし、ヘッダ/明細/トレーラ（H/D/T）構造を検証、チェックサム照合・却下率閾値判定を行って、後段（10-txnvalidate）が処理可能な**デコード済取引レコード**を生成する。**取引パイプラインの入口**となる。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | `OPS-BATCH-DAILY` → TO-BE: `TxnPipelineOrchestrator` | 日次バッチの先頭ステージとして起動 |
| 下流（呼び出し先） | `10-txnvalidate`（`DecodedBatch` を渡す） / `21-audit`（`AUD-WRITE`） | デコード結果を後段へ、開始/終了を監査記録 |
| 参照データ | EBCDIC 入力ファイル（Blob/ローカル）、sentinel ファイル | 一次データは外部連携ファイル |
| 共通部品 | [shared/util/ebc-to-ascii/](../../../shared/util/ebc-to-ascii/)（CP930→UTF-8）、[shared/copy/ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy) | 文字変換・出力レコード定義 |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH | TO-BE Java |
|---|---|---|---|---|
| `INTI-DECODE-BATCH` | [src/inti-decode-batch.cob](../../../subsystems/19-integrationin/src/inti-decode-batch.cob) | EBCDIC→ASCII デコード・検証・チェックサム照合 | `MAIN-LOGIC`, `CHECK-SENTINEL`, `PROCESS-EBCDIC-LOOP`, `DECODE-EBCDIC-RECORD`, `HANDLE-DETAIL`, `PARSE-DETAIL-FIELDS`, `TRANSLATE-CAT`, `TRANSLATE-DATE`, `VALIDATE-AMOUNT`, `CHECK-ACCT-FORMAT`, `ACCUMULATE-CHECKSUM`, `VERIFY-TRAILER` | `DecodeStage.decode()` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | バッチ（パイプライン第1ステージの関数呼び出し） |
| 実行契機 | 日次バッチ起動時。AS-IS は systemd timer → TO-BE は Container Apps Jobs / CronJob |
| 多重度・冪等性 | 副作用なし（純粋変換）。同一ファイルの再実行は同一結果（冪等）。sentinel で入力到着を確認 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
1. 入力検証（INTI-BATCH-ID / BUSINESS-DATE / 入力ファイル名）
2. sentinel チェック（INTI-REQUIRE-SENTINEL="Y" のとき sentinel ファイル存在を確認 → 無ければ 01 終了）
3. 入力 EBCDIC ファイルを 800 バイト固定長で逐次 READ
4. レコード種別判定（先頭1バイト H/D/T）
   - H: ヘッダ保持・チェックサム加算
   - D: 明細デコード（下記 2.2）→ 正常は decoded へ、不正は reject へ
   - T: トレーラのレコード数・チェックサム照合
5. トレーラ検証（期待件数 = デコード件数か）
6. 却下率算出（INTI-REJECT-THRESHOLD-PCT 超過なら PARTIAL + 警告）
7. カウンタを出力構造へ転記し終了
```

TO-BE では 3〜4 を `Stream<DecodedTxn>` の遅延評価で逐次処理し、中間ファイルを生成しない。

### 2.2 主要ロジック・業務ルール（明細デコード `HANDLE-DETAIL`）

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | `PARSE-DETAIL-FIELDS` | EBCDIC 固定位置から銀行/支店/区分/金額/口座/摘要/SEQ/商品/日付を切り出し（`NUMVAL` で数値化） |
| 2 | `TRANSLATE-CAT` | 取引区分を内部 2 桁へ変換。未知区分は `E105` で却下 |
| 3 | `TRANSLATE-DATE` | YY を Y2K 補正（`YY < 50` → 20YY、else 19YY）。月 1–12・日範囲を検証、不正は却下 |
| 4 | `VALIDATE-AMOUNT` | 金額ゼロを却下 |
| 5 | `CHECK-ACCT-FORMAT` | payer 口座 13 桁が数字でなければ `E106` で却下 |
| 6 | `ACCUMULATE-CHECKSUM` | 1 レコード 800 バイトのバイト値を加算（XOR/合算）し、トレーラ値と照合 |
| 7 | 却下率判定 `FINALIZE-OUTPUT` | `rejected/(decoded+rejected) > 閾値` で警告・PARTIAL |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 全明細デコード成功・トレーラ一致・却下率閾値内 |
| `01` | 入力未着 | sentinel 要求時に sentinel ファイルが存在しない（no-op 終了） |
| `04` | 一部却下 | デコード成功するが却下が発生（却下率閾値超過含む） |
| `08` | 入力不正 | ヘッダ/トレーラ欠落・バッチID/業務日不整合 |
| `12` | I/O失敗 | 入力/出力/reject ファイルのオープン・読み書き失敗 |
| `16` | 致命的 | 想定外の致命エラー |

> `01` は本サブシステム固有（入力未着）。詳細共通規約は [specs-asis/00-overview.md](../specs-asis/00-overview.md)。

### 2.4 排他・トランザクション制御

- AS-IS: 純粋なファイル変換でトランザクションなし。閾値超過時は出力ファイルを破棄する `CALL "SYSTEM"` あり。
- TO-BE: **副作用なしの純粋変換**。出力はインメモリ `DecodedBatch`。閾値超過時は結果を `PARTIAL` とし、後段に渡すかをオーケストレータが判断。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 入力ファイルオープン失敗 | 戻り値 `12` でリターン | AS-IS: `AUD-WRITE`（`BATCH_DECODE_START/END`） / TO-BE: 構造化ログ + 監査 |
| 却下率閾値超過 | `PARTIAL`・警告監査（severity=`W`） | `AUD-WRITE`（[shared/copy/aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy)） |
| 明細単位の却下 | reject 行へ理由コード（`E1xx`）出力 | reject ファイル / TO-BE: `List<TxnError>` |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/inti-api.cpy](../../../subsystems/19-integrationin/copy/api/inti-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `INTI-BATCH-ID` | `X(14)` | ✓ | バッチID | 非空白 |
| `INTI-BUSINESS-DATE` | `9(8)` | ✓ | 業務日（YYYYMMDD） | ≠ 0 |
| `INTI-INPUT-FILENAME` | `X(120)` | ✓ | EBCDIC 入力ファイルパス | 非空白 |
| `INTI-OUTPUT-FILENAME` | `X(120)` | ✓ | デコード済出力パス | TO-BE では未使用（インメモリ化） |
| `INTI-REJECT-FILENAME` | `X(120)` | ✓ | 却下出力パス | TO-BE では `List<TxnError>` |
| `INTI-SENTINEL-FILENAME` | `X(120)` | — | sentinel ファイルパス | sentinel 要求時のみ |
| `INTI-REJECT-THRESHOLD-PCT` | `9(3)` | ✓ | 却下率閾値（%） | 0–100 |
| `INTI-REQUIRE-SENTINEL` | `X(1)` | ✓ | sentinel 要否 | `Y`/`N`（88: `INTI-SENTINEL-YES/NO`） |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 入力ファイル | EBCDIC 取引ファイル | 固定長 800 バイト順次 | — | H/D/T 構造。TO-BE: Blob Storage / ローカル |
| sentinel | sentinel ファイル | 存在判定のみ | — | 入力到着の合図 |

### 3.3 前提・事前条件

- EBCDIC 入力ファイルが連携配置済みであること（sentinel 要求時は sentinel も）。
- 文字コードは CP930（IBM930）。TO-BE は JDK `Charset.forName("Cp930")` で変換。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `INTI-STATUS` | `X(2)` | 戻り値コード | 全ケースで設定（88: `INTI-OK`/`NO-INPUT-READY`/`PARTIAL`/`INVALID-INPUT`/`IO-FAIL`/`FATAL`） |
| `INTI-OUT-RECORDS-READ` | `9(10)` | 読込総レコード数 | 全レコード（H/D/T） |
| `INTI-OUT-DETAILS-DECODED` | `9(10)` | デコード成功明細数 | 正常 D の件数 |
| `INTI-OUT-DETAILS-REJECTED` | `9(10)` | 却下明細数 | 不正 D の件数 |
| `INTI-OUT-REJECT-PCT` | `9(3)` | 却下率（%） | `rejected/(decoded+rejected)*100` |
| `INTI-OUT-CHECKSUM-MATCH` | `X(1)` | チェックサム一致 | `Y`/`N`（88: `INTI-CHECKSUM-OK/MISMATCH`） |
| `INTI-OUT-DURATION-SEC` | `9(5)` | 処理秒数 | 計測値 |

### 4.2 出力データ更新

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| デコード出力 | `decoded.dat`（AS-IS） | WRITE | H/D/T レコード | TO-BE: `DecodedBatch`（インメモリ） |
| 却下出力 | `reject` ファイル（AS-IS） | WRITE | 理由コード付き行 | TO-BE: `List<TxnError>` |
| 監査ログ | 21-audit | 記録 | action=`BATCH_DECODE_START` / `BATCH_DECODE_END` | severity `I`/`W` |

### 4.3 後続・事後条件

- デコード済明細（`TXN-DECODED-DETAIL`）が後段 10-txnvalidate に渡る。
- 監査アウトボックス相当に開始/終了イベントが記録される。
- `01`（入力未着）の場合、後段は実行されずパイプラインは正常終了。

---

## 5. レコード定義

出力レコード: [shared/copy/ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy)（600 バイト固定、H/D/T を REDEFINES）

### ヘッダ（`TXN-DECODED-HEADER`）

| フィールド名 | PIC | 説明 |
|---|---|---|
| `TDH-REC-TYPE` | `X(1)` | `"H"` |
| `TDH-BATCH-ID` | `X(14)` | バッチID |
| `TDH-BUSINESS-DATE` | `9(8)` | 業務日 |
| `TDH-SOURCE-SYSTEM` | `X(20)` | 例 `EBCDIC_BATCH` |
| `TDH-EXPECTED-COUNT` | `9(10)` | 期待明細件数 |
| `TDH-CHECKSUM` | `X(40)` | チェックサム |

### 明細（`TXN-DECODED-DETAIL`）

| フィールド名 | PIC | 説明 |
|---|---|---|
| `TDD-REC-TYPE` | `X(1)` | `"D"` |
| `TDD-SEQ` | `9(10)` | シーケンス番号 |
| `TDD-CATEGORY` | `X(2)` | 取引区分（88: 10入金/20出金/30振替/40仕向送金/50利息/60手数料） |
| `TDD-AMOUNT-JPY` | `9(15)` | 金額（円・整数） |
| `TDD-CURRENCY` | `X(3)` | 通貨（`JPY`） |
| `TDD-PAYER-ACCT` | `X(13)` | 支払元口座 |
| `TDD-PAYEE-ACCT` | `X(13)` | 受取口座（振替/送金時） |
| `TDD-BRANCH-CODE` | `9(3)` | 支店コード |
| `TDD-PRODUCT-CODE` | `9(3)` | 商品コード |
| `TDD-DESCRIPTION` | `X(120)` | 摘要 |
| `TDD-SOURCE-BANK` | `X(4)` | 源泉銀行 |
| `TDD-SOURCE-BRANCH` | `X(3)` | 源泉支店 |
| `TDD-ORIGINAL-SEQ` | `9(10)` | 元取引SEQ |

### トレーラ（`TXN-DECODED-TRAILER`）

| フィールド名 | PIC | 説明 |
|---|---|---|
| `TDT-REC-TYPE` | `X(1)` | `"T"` |
| `TDT-RECORD-COUNT` | `9(10)` | 明細件数 |
| `TDT-AMOUNT-SUM` | `9(20)` | 金額合計 |
| `TDT-CHECKSUM` | `X(40)` | チェックサム |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | EBCDIC 文字変換 | C util `ebc-to-ascii`（CP930→UTF-8）を外部 CALL | JDK `Charset.forName("Cp930")` で内製変換 | 外部プロセス排除 |
| 2 | 中間ファイル | `decoded.dat`（600byte）を WRITE | `DecodedBatch{header, Stream<DecodedTxn>, trailer}` をインメモリ受け渡し | ファイル廃止 |
| 3 | 却下出力 | reject ファイルへ理由コード行 | `List<TxnError>`（理由コード `E1xx` 保持） | オブジェクト化 |
| 4 | 固定位置パース | `WS-DECODED-BUF(開始:長さ)` で切り出し | バイト配列の固定オフセットパーサ（`record.slice(offset,len)`） | レイアウト定数を集約 |
| 5 | sentinel 確認 | `CALL "SYSTEM" "test -f"` | `Files.exists(Path)` | OS 依存排除 |
| 6 | 閾値超過時の出力破棄 | `CALL "SYSTEM"` でファイル削除 | 結果を `PARTIAL` とし後段判断（破棄しない） | 副作用排除 |
| 7 | チェックサム | バイト合算（`WS-CHECKSUM-ACC`） | 同一アルゴリズムを Java で再現（互換必須） | バイト等価を単体テストで保証 |

### TO-BE Java 関数マッピング

```java
public final class DecodeStage implements PipelineStage<DecodeRequest, DecodedBatch> {
    // INTI-DECODE-BATCH 相当。EBCDIC 800byte を逐次デコードし H/D/T を構築。
    StageResult<DecodedBatch> execute(DecodeRequest req, BatchContext ctx);

    // PARSE-DETAIL-FIELDS + TRANSLATE-* + VALIDATE-* 相当
    private Either<TxnError, DecodedTxn> decodeDetail(byte[] ebcdic800);

    // ACCUMULATE-CHECKSUM 相当（AS-IS とバイト等価）
    private long accumulateChecksum(byte[] record);
}
```

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | チェックサム・アルゴリズムの厳密仕様（XOR か合算か桁あふれ挙動） | AS-IS コードと[shared/util/](../../../shared/util/)を突合し、ゴールデンデータでバイト等価を確認 | — | — |
| 2 | EBCDIC 固定オフセットの全項目マップ | `PARSE-DETAIL-FIELDS` の各 `(開始:長さ)` を定数表として抽出 | — | — |
| 3 | 入力ソース（Blob か ファイル）と sentinel の TO-BE 実装 | 横断 [99 §7](99-pipeline-orchestration-design.md) のデプロイ形態で確定 | — | — |

---

*テンプレートバージョン: 1.0 / 参照: [specs-asis/02-transaction-pipeline.md](../specs-asis/02-transaction-pipeline.md), [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)*
