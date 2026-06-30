# サブシステム設計書（TO-BE） — 11-txnsortmerge

> COBOL `TXSM-SORT-BATCH` / `TXSM-MERGE-BATCH` を Java の **`SortMergeStage`** へ移行する TO-BE 設計。
> AS-IS 一次情報: [specs-asis/02-transaction-pipeline.md § 11](../specs-asis/02-transaction-pipeline.md) / [src/txsm-sort-batch.cob](../../../subsystems/11-txnsortmerge/src/txsm-sort-batch.cob) / [src/txsm-merge-batch.cob](../../../subsystems/11-txnsortmerge/src/txsm-merge-batch.cob)
> 横断方針: [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `11-txnsortmerge` |
| ディレクトリ | [subsystems/11-txnsortmerge/](../../../subsystems/11-txnsortmerge/) |
| 分類 | トランザクション処理系（ソート・突合） |
| API契約 | [copy/api/tx-sm-api.cpy](../../../subsystems/11-txnsortmerge/copy/api/tx-sm-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

検証済取引を **payer 口座 + シーケンス番号で昇順ソート**し（記帳順序の確定・口座単位ブロック化）、さらに**前営業日の確定済取引（recon-prev）と 2-way マージ**して重複（再投入）を検出する。最終的に 12-txnpost が記帳可能な **ready 取引列**を生成する。無損失（件数保存）・コントロールトータル（金額保存）を検証する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | `10-txnvalidate`（`ValidationResult` の valid） | 検証済明細を受け取る |
| 下流（呼び出し先） | `12-txnpost`（`List<ReadyTxn>`） | ソート/突合済を記帳へ |
| 参照データ | 前営業日 ready（recon-prev） | 重複検出のため前日確定分を参照 |
| 共通部品 | SORT（COBOL SORT 文）、[shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) | ソート・ログ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH | TO-BE Java |
|---|---|---|---|---|
| `TXSM-SORT-BATCH` | [src/txsm-sort-batch.cob](../../../subsystems/11-txnsortmerge/src/txsm-sort-batch.cob) | payer+seq 昇順ソート・無損失検証 | `M-START`, `EXECUTE-SORT`, `SORT-INPUT-PARA`, `SORT-OUTPUT-PARA`, `VERIFY-LOSSLESS-INVARIANT`, `PUBLISH-COUNTERS` | `SortMergeStage.sort()` |
| `TXSM-MERGE-BATCH` | [src/txsm-merge-batch.cob](../../../subsystems/11-txnsortmerge/src/txsm-merge-batch.cob) | 前日 recon と 2-way マージ・重複検出 | `M-START`, `CHECK-RECON-EXISTS`, `VERIFY-RECON-SORT-ORDER`, `MERGE-2WAY-WITH-RECON`, `HANDLE-DUPLICATE-AT-MERGE`, `WRITE-FINAL-READY`, `SET-FINAL-STATUS` | `SortMergeStage.merge()` |
| `TXSM-REPORT-SUMMARY` | [src/txsm-report-summary.cob](../../../subsystems/11-txnsortmerge/src/txsm-report-summary.cob) | サマリレポート | `MAIN-LOGIC` | `SortMergeReport`（任意） |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | バッチ（パイプライン第3ステージの関数呼び出し） |
| 実行契機 | 10 の検証完了後 |
| 多重度・冪等性 | 副作用なし（純粋変換）。前日 recon 参照は読み取りのみ |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[SORT]
1. 入力（valid）を読み込み、H/T を退避、D を payer+seq キーで RELEASE
2. SORT 実行（昇順）、RETURN で sorted を生成（H + D* + T）
3. 無損失検証（入力 D 件数 = 出力 D 件数、金額合計一致）
[MERGE]
4. 前日 recon の存在チェック（無ければ PASSTHROUGH）
5. recon のソート順序検証（payer+seq 昇順）
6. sorted と recon を 2-way マージ
   - sorted < recon → sorted を出力
   - sorted > recon → recon を出力
   - sorted = recon → 重複（E050）→ error へ、両者スキップ
7. 最終 ready を生成（H + マージ済 D* + T）
8. 保存則検証（(sorted_in + recon_in) = (merged_out + duplicates)）
```

TO-BE: SORT はインメモリ `List.sort(Comparator.comparing(payer).thenComparing(seq))`、MERGE は前日 ready を PG テーブル `daily_ready` から読む（横断 §6-B）。

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | ソートキー | payer 口座（昇順）→ シーケンス番号（昇順）。口座単位ブロック化で 12 の `OPEN-ACCT-BLOCK` を成立させる |
| 2 | 無損失（SORT） | `VERIFY-LOSSLESS-INVARIANT`：入力件数 = 出力件数。違反は致命扱い |
| 3 | コントロールトータル | ヘッダ期待件数・金額合計と実績を照合。不一致は WARNING → PARTIAL |
| 4 | recon 存在判定 | 前日ファイル無し（FS=35）→ PASSTHROUGH（sorted を素通し） |
| 5 | recon ソート順検証 | recon が payer+seq 昇順でなければ SORT-VIOLATION → `08` INVALID |
| 6 | 重複検出（MERGE） | sorted と recon のキー一致 = 再投入。理由コード `E050` で却下、両者スキップ |
| 7 | 保存則（MERGE） | `(sorted_in + recon_in) = (merged_out + duplicates)`。不一致は INVALID |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | ソート/マージ成功・保存則一致・重複なし |
| `04` | 一部却下 | 重複検出あり（PARTIAL） |
| `08` | 入力不正 | recon ソート順違反・保存則不一致 |
| `12` | I/O失敗 | ファイルオープン/読み書き失敗 |
| `16` | 致命的 | 無損失違反等の致命エラー |

> SORT: 88 `TXSM-SO-OK/PARTIAL/INVALID/IO-FAIL/FATAL`。MERGE: 88 `TXSM-MO-OK/...`。

### 2.4 排他・トランザクション制御

- AS-IS: ファイルソート/マージでトランザクションなし。一時ファイル（temp）を経由し最後に ready へ転記、temp は削除。
- TO-BE: **副作用なし**。ソートはインメモリ `List` 確定（全件メモリ）。前日 recon は PG `daily_ready` の読み取りのみ。当日確定分は段 12 成功後に `daily_ready` へ書き込む（翌日の recon 用、横断 §6-B）。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| recon ソート順違反 | `SORT-VIOLATIONS++` → `08` | SHARED-LOG（WARN） |
| 重複検出 | error 出力へ `E050` 行 | `SORT-BATCH batch=...,in=,out=,ctrl=,status=` |
| 無損失違反 | 致命扱い（`16`） | SHARED-LOG（ERROR） |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/tx-sm-api.cpy](../../../subsystems/11-txnsortmerge/copy/api/tx-sm-api.cpy)

**SORT 入力（`TXSM-SORT-INPUT`）**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `TXSM-SI-BATCH-ID` | `X(14)` | ✓ | バッチID |
| `TXSM-SI-BUSINESS-DATE` | `9(8)` | ✓ | 業務日 |
| `TXSM-SI-INPUT-FILENAME` | `X(80)` | ✓ | 入力（valid）パス。TO-BE: `List<ValidTxn>` |
| `TXSM-SI-OUTPUT-FILENAME` | `X(80)` | ✓ | sorted 出力パス。TO-BE: インメモリ |
| `TXSM-SI-CHECKPOINT-FILENAME` | `X(80)` | — | 未使用 |

**MERGE 入力（`TXSM-MERGE-INPUT`）**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `TXSM-MI-BATCH-ID` | `X(14)` | ✓ | バッチID |
| `TXSM-MI-BUSINESS-DATE` | `9(8)` | ✓ | 業務日 |
| `TXSM-MI-SORTED-FILENAME` | `X(80)` | ✓ | sorted 入力 |
| `TXSM-MI-RECON-PREV-FILENAME` | `X(80)` | ✓ | 前日 recon。TO-BE: PG `daily_ready` |
| `TXSM-MI-READY-FILENAME` | `X(80)` | ✓ | ready 出力。TO-BE: `List<ReadyTxn>` |
| `TXSM-MI-ERROR-FILENAME` | `X(80)` | ✓ | 重複エラー出力 |
| `TXSM-MI-CHECKPOINT-FILENAME` | `X(80)` | — | 未使用 |
| `TXSM-MI-TEMP-FILENAME` | `X(80)` | ✓ | 一時ファイル。TO-BE: 不要 |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 入力ファイル | `valid.dat` | 600byte 順次 | — | TO-BE: 10 の `List<ValidTxn>` |
| 前日 recon | `recon-prev`（前日 ready） | 600byte 順次 | payer+seq | TO-BE: PG `daily_ready`（横断 §6-B） |

### 3.3 前提・事前条件

- 10 の検証済明細（valid）が得られていること。
- 前営業日の ready 確定分が参照可能であること（無ければ PASSTHROUGH）。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

**SORT 出力（`TXSM-SORT-OUTPUT`）**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `TXSM-SO-STATUS` | `X(2)` | 戻り値コード |
| `TXSM-SO-RECORDS-PROCESSED` | `9(7)` | 入力明細数 |
| `TXSM-SO-RECORDS-SORTED` | `9(7)` | 出力明細数 |
| `TXSM-SO-CTRL-TOTAL-MATCH` | `X(1)` | ヘッダ期待数と一致（`Y`/`N`） |
| `TXSM-SO-AMOUNT-SUM` | `9(20)` | 金額合計 |

**MERGE 出力（`TXSM-MERGE-OUTPUT`）**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `TXSM-MO-STATUS` | `X(2)` | 戻り値コード |
| `TXSM-MO-RECORDS-SORTED-IN` | `9(7)` | sorted 入力件数 |
| `TXSM-MO-RECORDS-RECON-IN` | `9(7)` | recon 入力件数 |
| `TXSM-MO-RECORDS-MERGED-OUT` | `9(7)` | マージ出力件数 |
| `TXSM-MO-DUPLICATE-RECORDS` | `9(5)` | 重複レコード数 |
| `TXSM-MO-DUPLICATE-PAIRS` | `9(5)` | 重複ペア数 |
| `TXSM-MO-SORT-VIOLATIONS` | `9(5)` | ソート順違反数 |
| `TXSM-MO-RECON-PRESENT-FLAG` | `X(1)` | recon 存在（`Y`/`N`） |
| `TXSM-MO-AMOUNT-SUM` | `9(20)` | 金額合計 |

### 4.2 出力データ更新

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| sorted 出力 | `sorted.dat`（[fd-txn-sorted.cpy](../../../subsystems/11-txnsortmerge/copy/private/fd-txn-sorted.cpy)） | WRITE | ソート済明細 | TO-BE: インメモリ |
| ready 出力 | `ready.dat`（[fd-txn-ready.cpy](../../../subsystems/11-txnsortmerge/copy/private/fd-txn-ready.cpy)） | WRITE | マージ済明細 | TO-BE: `List<ReadyTxn>` |
| 重複エラー | `error.dat`（[fd-txn-error.cpy](../../../subsystems/11-txnsortmerge/copy/private/fd-txn-error.cpy)） | WRITE | `E050` 行 | TO-BE: `List<TxnError>` |

### 4.3 後続・事後条件

- ready 取引列（payer+seq 昇順）が 12-txnpost に渡る。
- 重複は `E050` で除外され、保存則検証が成立する。

---

## 5. レコード定義

ソート作業: [copy/private/sd-txn-sort.cpy](../../../subsystems/11-txnsortmerge/copy/private/sd-txn-sort.cpy)（SORT WORK）。
ready: [copy/private/fd-txn-ready.cpy](../../../subsystems/11-txnsortmerge/copy/private/fd-txn-ready.cpy)、recon: [copy/private/fd-txn-recon-prev.cpy](../../../subsystems/11-txnsortmerge/copy/private/fd-txn-recon-prev.cpy)。明細項目は [shared/copy/ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy) と同系。

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `TDD-PAYER-ACCT` | `X(13)` | ソート主キー | payer 口座（昇順） |
| `TDD-SEQ` | `9(10)` | ソート副キー | シーケンス番号（昇順） |
| `TDD-AMOUNT-JPY` | `9(15)` | — | 金額（コントロールトータル対象） |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | ソート | COBOL `SORT` 文 + SORT WORK ファイル | `List.sort(Comparator.comparing(payer).thenComparing(seq))` | インメモリソート |
| 2 | 中間/一時ファイル | sorted.dat / temp / ready.dat | インメモリ `List<ReadyTxn>`、temp 廃止 | ファイル廃止 |
| 3 | 前日 recon | 前日 `ready.dat` ファイルを参照 | PG テーブル `daily_ready` を参照、当日分は段12成功後に書込 | 横断 §6-B |
| 4 | 2-way マージ | ファイルポインタの手動マージ | `Iterator` ベースの 2-way マージ or `TreeMap` 突合 | 重複は `E050` |
| 5 | 無損失/保存則 | パラグラフ内カウンタ検証 | ステージ境界アサーション（横断 §3.2） | 維持 |

### TO-BE Java 関数マッピング

```java
public final class SortMergeStage implements PipelineStage<ValidationResult, SortMergeResult> {
    StageResult<SortMergeResult> execute(ValidationResult in, BatchContext ctx);

    // TXSM-SORT-BATCH 相当：payer+seq 昇順ソート + 無損失検証
    private List<SortedTxn> sort(List<ValidTxn> valid);

    // TXSM-MERGE-BATCH 相当：前日 daily_ready と 2-way マージ、重複 E050 検出
    private MergeOutcome merge(List<SortedTxn> sorted, List<ReconTxn> prevDayReady);
}

public record SortMergeResult(List<ReadyTxn> ready, List<TxnError> duplicates,
                              boolean reconPresent, long amountSum) {}
```

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | 前日 recon データの所在（PG テーブル `daily_ready` か ファイルか） | 横断 §6-B の既定方針（PG永続）をレビューで確定。Flyway V8 要否（横断 §8-1） | — | — |
| 2 | 大量件数時のソートメモリ | 件数上限を試算し、超過時は外部ソート/Spring Batch を検討（横断 §6-C） | — | — |
| 3 | `daily_ready` への書込タイミング | 段 12 成功後に当日確定分を登録する責務の所在を決定 | — | — |

---

*テンプレートバージョン: 1.0 / 参照: [specs-asis/02-transaction-pipeline.md](../specs-asis/02-transaction-pipeline.md), [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)*
