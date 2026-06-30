# サブシステム設計書（TO-BE） — 10-txnvalidate

> COBOL `TXVAL-VALIDATE-BATCH` ほかを Java の **`ValidateStage`** へ移行する TO-BE 設計。
> AS-IS 一次情報: [specs-asis/02-transaction-pipeline.md § 10](../specs-asis/02-transaction-pipeline.md) / [src/txval-validate-batch.cob](../../../subsystems/10-txnvalidate/src/txval-validate-batch.cob)
> 横断方針: [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `10-txnvalidate` |
| ディレクトリ | [subsystems/10-txnvalidate/](../../../subsystems/10-txnvalidate/) |
| 分類 | トランザクション処理系（検証） |
| API契約 | [copy/api/tx-val-api.cpy](../../../subsystems/10-txnvalidate/copy/api/tx-val-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

19-integrationin がデコードした取引明細を、**マスタ（カレンダー/支店/商品）照合**と**業務ルール検証**にかけ、正常レコードと却下レコードに振り分ける。却下は理由コード（`E0xx`）付きで記録し、却下コード別カウンタを集計する。AS-IS はチェックポイントによる再開機能を持つ。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | `19-integrationin`（`DecodedBatch`） | デコード済明細を受け取る |
| 下流（呼び出し先） | `11-txnsortmerge`（`ValidationResult` の valid を渡す） | 検証済取引を後段へ |
| 参照データ | カレンダー/支店/商品マスタ（AS-IS: ISAM 由来のメモリキャッシュ） | マスタ照合用 |
| 共通部品 | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy)（SHARED-LOG） | 構造化ログ（AUD-WRITE は不使用） |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH | TO-BE Java |
|---|---|---|---|---|
| `TXVAL-VALIDATE-BATCH` | [src/txval-validate-batch.cob](../../../subsystems/10-txnvalidate/src/txval-validate-batch.cob) | 検証本体・マスタ照合・カウンタ集計 | `M-START`, `LOAD-MASTER-CACHE`, `PROCESS-LOOP`, `PROCESS-HEADER/DETAIL/TRAILER`, `CHECK-E002`〜`E019`, `CHECK-MASTER-LOOKUPS`, `CHECKPOINT-MAYBE`, `EMIT-AUDIT-SUMMARY` | `ValidateStage.validate()` |
| `TXVAL-CHECKPOINT-RECOVER` | [src/txval-checkpoint-recover.cob](../../../subsystems/10-txnvalidate/src/txval-checkpoint-recover.cob) | チェックポイント読込・再開位置抽出 | `MAIN-LOGIC` | （横断 §6-A で原則廃止） |
| `TXVAL-REPORT-SUMMARY` | [src/txval-report-summary.cob](../../../subsystems/10-txnvalidate/src/txval-report-summary.cob) | サマリレポート生成 | `MAIN-LOGIC` | `ValidationReport`（任意） |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | バッチ（パイプライン第2ステージの関数呼び出し） |
| 実行契機 | 19 のデコード完了後 |
| 多重度・冪等性 | 副作用なし（純粋変換）。同一入力で同一結果（冪等）。AS-IS のチェックポイント再開は TO-BE で原則廃止（横断 §6-A） |

---

## 2. 処理詳細

### 2.1 処理フロー

```
1. 出力エリア初期化
2. マスタキャッシュロード（LOAD-MASTER-CACHE）
   - カレンダー（約1830日 = 5年） / 支店（50件） / 商品（20件）
3. 入力（decoded）を逐次処理（PROCESS-LOOP）
   - H: バッチID・業務日・期待件数を保持/照合
   - D: 検証ルール群を適用（2.2）。全通過→valid、いずれか違反→error（理由コード付き）
   - T: 件数・金額合計（コントロールトータル）を照合
4. チェックポイント保存（AS-IS。TO-BE 廃止）
5. カウンタ（処理/検証OK/却下、PRI/OCC 別）を出力へ転記
6. SHARED-LOG にサマリ出力
```

### 2.2 主要ロジック・業務ルール（明細検証 `PROCESS-DETAIL`）

1 レコードに複数エラーが発生しうる。**Primary Error**（代表）と **Occurrence Error**（発生した全エラー）を別集計する。

| 検査 | コード | 内容 |
|---|---|---|
| `CHECK-E002-CATEGORY` | E002 | 取引区分が 10/20/30/40/50/60 のいずれか |
| `CHECK-E003-ACCOUNT-FORMAT` | E003 | payer 口座が数字 13 桁 |
| `CHECK-E007-COUNTER-MISSING` | E007 | 振替/送金（30/40）で受取口座が必須 |
| `CHECK-E008-SELF-TRANSFER` | E008 | 自口座間の送金禁止（payer = payee） |
| `CHECK-E009-AMOUNT-ZERO` | E009 | 金額ゼロ禁止 |
| `CHECK-E010-AMOUNT-LIMIT` | E010 | 金額上限（¥100,000,000）超過禁止 |
| `CHECK-E013-CURRENCY` | E013 | 通貨は `JPY` 必須 |
| `CHECK-E018-COUNTER-NON-TRANSFER` | E018 | 非送金で受取口座が設定されていたら不正 |
| `CHECK-E019-TD-WITHDRAW` | E019 | 定期商品口座からの払出禁止 |
| `CAL-CACHE-LOOKUP` | E012 | 業務日が営業日であること |
| `BR-CACHE-LOOKUP` | E014 | 支店コードがマスタに存在 |
| `PROD-CACHE-LOOKUP` | E015 / E016 | 商品が存在（E015）かつ有効期間内（E016） |

**Primary Error 優先度**: `E001→E002→E003→E009→E013→E017→E012→E014→E015→E016→E007→E008→E010→E011→E018→E019→E099`

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 全レコード検証OK（88: `TXVAL-OK`） |
| `04` | 一部却下 | 一部レコードが検証NG（88: `TXVAL-PARTIAL-REJECT`） |
| `08` | 入力不正 | ヘッダ/トレーラ不正、バッチID/業務日ミスマッチ（88: `TXVAL-INVALID-INPUT`） |
| `12` | I/O失敗 | ファイルオープン失敗（88: `TXVAL-IO-FAIL`） |
| `16` | 致命的 | マスタキャッシュロード失敗等（88: `TXVAL-FATAL`） |

**チェックポイント回復**（`TXVAL-CR-STATUS`）: `00`=発見 / `04`=無し（新規開始） / `12`=破損 / `16`=致命的。`TXVAL-CR-OUT-LAST-SEQ`(9(10)) で再開位置を返す。

### 2.4 排他・トランザクション制御

- AS-IS: 純粋なファイル検証でトランザクションなし。チェックポイントファイル（`TC-SENTINEL="OK"` + `TC-LAST-SEQ`）で再開位置を永続。
- TO-BE: **副作用なし**。出力は `ValidationResult`（インメモリ）。チェックポイントは横断 §6-A の方針で原則廃止し、再実行の冪等は段 12 の `txn_id` 一意制約に集約。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| マスタキャッシュロード失敗 | 戻り値 `16` でリターン | SHARED-LOG（ERROR） |
| 入力ファイルオープン失敗 | 戻り値 `12` | SHARED-LOG |
| 明細単位の検証NG | error 出力へ理由コード付き行 | `VALIDATE-BATCH batch=...,proc=,valid=,rej=,status=` |

> AS-IS は監査記録に `AUD-WRITE` を使わず [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy)（SHARED-LOG）のみ。

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/tx-val-api.cpy](../../../subsystems/10-txnvalidate/copy/api/tx-val-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `TXVAL-IN-BATCH-ID` | `X(14)` | ✓ | バッチID | ヘッダと一致すること |
| `TXVAL-IN-BUSINESS-DATE` | `9(8)` | ✓ | 業務日（YYYYMMDD） | 営業日 |
| `TXVAL-IN-INPUT-FILENAME` | `X(80)` | ✓ | 入力（decoded）パス | TO-BE: `DecodedBatch` |
| `TXVAL-IN-VALID-FILENAME` | `X(80)` | ✓ | 正常出力パス | TO-BE: `List<ValidTxn>` |
| `TXVAL-IN-ERROR-FILENAME` | `X(80)` | ✓ | 却下出力パス | TO-BE: `List<TxnError>` |
| `TXVAL-IN-CHECKPOINT-FILENAME` | `X(80)` | — | チェックポイントパス | TO-BE: 原則未使用 |

チェックポイント回復 I/F: `TXVAL-CR-IN-FILENAME PIC X(80)` → `TXVAL-CR-STATUS` / `TXVAL-CR-OUT-LAST-SEQ`。

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 入力ファイル | `decoded.dat` | 600byte 順次（H/D/T） | — | TO-BE: 19 からの `DecodedBatch` |
| マスタ | カレンダー/支店/商品 | AS-IS: メモリキャッシュ（ISAM由来） | 各コード | TO-BE: PostgreSQL 参照 or 起動時キャッシュ |
| テーブル | `calendar` / `branches` / `products` | PostgreSQL | 各PK | TO-BE 参照先 |

### 3.3 前提・事前条件

- 19 のデコードが完了し `DecodedBatch` が得られていること。
- マスタ（カレンダー/支店/商品）が参照可能であること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `TXVAL-BATCH-STATUS` | `X(2)` | 戻り値コード | 全ケースで設定 |
| `TXVAL-OUT-PROCESSED` | `9(7)` | 処理明細数（H/T除く） | 常時 |
| `TXVAL-OUT-VALIDATED` | `9(7)` | 検証OK数 | 常時 |
| `TXVAL-OUT-REJECTED` | `9(7)` | 却下数 | 常時 |
| `TXVAL-OUT-PRI-E001`〜`E099` | `9(7)` | Primary Error 別件数 | 代表エラーで1件計上 |
| `TXVAL-OUT-OCC-E001`〜`E099` | `9(7)` | Occurrence Error 別件数 | 発生した全エラーで計上 |

> 集計対象コード: E001/E002/E003/E007/E008/E009/E010/E011/E012/E013/E014/E015/E016/E017/E018/E019/E099。

### 4.2 出力データ更新

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 正常出力 | `valid.dat`（[fd-txn-valid.cpy](../../../subsystems/10-txnvalidate/copy/private/fd-txn-valid.cpy)） | WRITE | 検証済明細（H + D* + T） | TO-BE: `List<ValidTxn>` |
| 却下出力 | `error.dat`（[fd-txn-error.cpy](../../../subsystems/10-txnvalidate/copy/private/fd-txn-error.cpy)） | WRITE | 理由コード付きレコード | TO-BE: `List<TxnError>` |
| チェックポイント | `*.ckpt`（[fd-txn-checkpoint.cpy](../../../subsystems/10-txnvalidate/copy/private/fd-txn-checkpoint.cpy)） | WRITE | LastSeq/Checksum/Sentinel | TO-BE: 廃止 |

### 4.3 後続・事後条件

- 検証OK明細が 11-txnsortmerge に渡る。
- 却下明細は理由コード別に集計され、サマリログに出力。

---

## 5. レコード定義

却下レコード: [copy/private/fd-txn-error.cpy](../../../subsystems/10-txnvalidate/copy/private/fd-txn-error.cpy)

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `TEF-ORIG-SEQ` | `9(10)` | — | 元シーケンス番号 |
| `TEF-REASON-CODE` | `X(4)` | — | エラーコード（E001–E099） |
| `TEF-REASON-TEXT` | `X(80)` | — | エラー説明（複合エラーは列挙） |
| `TEF-ORIG-REC` | `X(600)` | — | 元レコード全体 |

チェックポイント: [copy/private/fd-txn-checkpoint.cpy](../../../subsystems/10-txnvalidate/copy/private/fd-txn-checkpoint.cpy)

| フィールド名 | PIC | 説明 |
|---|---|---|
| `TC-LAST-SEQ` | `9(10)` | 最後に処理したシーケンス |
| `TC-CHECKSUM` | `X(8)` | チェックサム（未使用） |
| `TC-SENTINEL` | `X(2)` | `"OK"` で妥当性判定 |

入力明細レイアウトは [shared/copy/ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy)（19 と共通）。

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | 中間ファイル | `valid.dat`/`error.dat` を WRITE | `ValidationResult{ List<ValidTxn>, List<TxnError> }` | インメモリ化 |
| 2 | マスタキャッシュ | ISAM 由来のメモリ配列（カレンダー1830/支店50/商品20） | PostgreSQL 直参照 or 起動時キャッシュ（`Map`） | データ規模で選択 |
| 3 | チェックポイント再開 | `.ckpt`（Sentinel/LastSeq） | **原則廃止**。冪等は段12の `txn_id` 一意制約に集約 | 横断 §6-A |
| 4 | 複合エラー集計 | PRI/OCC を固定フィールド群で集計 | `Map<ErrorCode,Integer>` の PRI/OCC 2 マップ | 構造簡素化 |
| 5 | Primary 優先度 | パラグラフ順の固定優先度 | `enum ErrorCode { ... }` の `priority` 属性で表現 | 明示化（順序保持） |
| 6 | ログ | SHARED-LOG 1 行サマリ | 構造化ログ + メトリクス | 監視連携 |

### TO-BE Java 関数マッピング

```java
public final class ValidateStage implements PipelineStage<DecodedBatch, ValidationResult> {
    // TXVAL-VALIDATE-BATCH 相当
    StageResult<ValidationResult> execute(DecodedBatch in, BatchContext ctx);

    // PROCESS-DETAIL + CHECK-E0xx 相当（全ルールを適用し PRI/OCC を集計）
    private Optional<TxnError> validateDetail(DecodedTxn d, MasterCache cache);

    // LOAD-MASTER-CACHE 相当
    private MasterCache loadMasterCache(LocalDate businessDate);
}

public record ValidationResult(List<ValidTxn> valid, List<TxnError> errors,
                               Map<ErrorCode,Integer> primary, Map<ErrorCode,Integer> occurrence) {}
```

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | マスタ参照を PG 直参照とするか起動時キャッシュとするか | データ規模・鮮度要件で決定（横断 §6 論点） | — | — |
| 2 | チェックポイント廃止の妥当性 | 横断 §6-A の冪等集約方針をレビューで確定 | — | — |
| 3 | エラーコード `E001/E011/E017` の発生条件詳細 | AS-IS コードを精読し各コードの条件を確定 | — | — |
| 4 | 金額上限（¥100M）の業務定数化 | 設定値として外出し | — | — |

---

*テンプレートバージョン: 1.0 / 参照: [specs-asis/02-transaction-pipeline.md](../specs-asis/02-transaction-pipeline.md), [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)*
