# AS-IS 機能仕様書 — 運用・監査（サブシステム 21〜22）

> 監査ログの長期保全・フォレンジック照会・パーティション運用（21）と、日次/月次バッチのオーケストレーション・ファイナライズ・運用ジョブ（22）。
> 共通規約は [00-overview.md](00-overview.md)、取引本流は [02-transaction-pipeline.md](02-transaction-pipeline.md) を参照。

## 目次

| # | サブシステム | 主プログラム | 役割 |
|---|---|---|---|
| 21 | audit | AUDIT-QUERY-FORENSIC / PARTITION-ROLLOVER / SUMMARY-REPORT | 監査ログ照会・保全 |
| 22 | operations | OPS-BATCH-DAILY / MONTHLY / FINALIZE / 他 | バッチ統括・運用 |

---

## 21-audit — 監査

API契約: [copy/api/audit-api.cpy](../../../subsystems/21-audit/copy/api/audit-api.cpy)（`AQF-*` / `APR-*` / `ASR-*` の3契約）

監査ログは月次レンジパーティション（[db/migration/V3__audit_log_partitioning.sql](../../../db/migration/V3__audit_log_partitioning.sql)）で管理。書込は **トランザクショナル・アウトボックス**（`audit_outbox`→aud-drain）経由で冪等に転記（[00-overview.md](00-overview.md) 参照）。

### プログラム

| Program-ID | ファイル | 機能 |
|---|---|---|
| `AUDIT-QUERY-FORENSIC` | [src/audit-query-forensic.sqb](../../../subsystems/21-audit/src/audit-query-forensic.sqb) | 期間・サブシステム・アクション・重大度・口座でのフォレンジック照会（TEXT/CSV/JSON出力） |
| `AUDIT-PARTITION-ROLLOVER` | [src/audit-partition-rollover.sqb](../../../subsystems/21-audit/src/audit-partition-rollover.sqb) | 翌月以降パーティションの先行作成・保持期限超過の DETACH |
| `AUDIT-SUMMARY-REPORT` | [src/audit-summary-report.sqb](../../../subsystems/21-audit/src/audit-summary-report.sqb) | 日別/サブシステム別の集計レポート |

DETACH補助: [src/detach-helper.sh](../../../subsystems/21-audit/src/detach-helper.sh)。

### フォレンジック照会（AQF）

- 入力 `AQF-INPUT`: `AQF-DATE-START`/`END`(9(8))、`AQF-SUBSYSTEM`(X(30))、`AQF-ACTION`(X(50))、`AQF-SEVERITY`(` `/`I`/`W`/`E`/`C`)、`AQF-ACCOUNT-FILTER`(X(13))、`AQF-MAX-ROWS`(9(5))、`AQF-OUTPUT-FORMAT`(`TEXT`/`CSV `/`JSON`)、`AQF-OUTPUT-FILENAME`(X(120))、`AQF-OPERATOR-USER`
- 出力 `AQF-OUTPUT`: `AQF-STATUS`(`00`/`08`/`12`/`16`)、`AQF-OUT-ROW-COUNT`(9(7))、`AQF-OUT-QUERY-ID`(X(36) UUID)、`AQF-OUT-DURATION-MS`

### パーティションロールオーバー（APR）

- 入力 `APR-INPUT`: `APR-OPERATOR-USER`、`APR-RETENTION-DAYS`(9(5))、`APR-DRY-RUN`(`Y`/`N`)、`APR-ENABLE-DETACH`(`Y`/`N`)
- 出力 `APR-OUTPUT`: `APR-STATUS`(`00`/`08`/`16`)、`APR-OUT-CREATED-COUNT`(9(3))、`APR-OUT-DETACHED-COUNT`(9(3))、`APR-OUT-NEXT-PARTITION`(X(20))
- パーティション関数は [db/migration/V5__audit_partition_functions.sql](../../../db/migration/V5__audit_partition_functions.sql) を利用。`DRY-RUN=Y` で件数のみ算出。

### サマリレポート（ASR）

- 入力 `ASR-INPUT`: `ASR-DATE-START`/`END`、`ASR-MODE`(`D`=日別/`S`=サブシステム別)、`ASR-OUTPUT-FILENAME`
- 出力 `ASR-OUTPUT`: `ASR-STATUS`(`00`/`08`/`12`/`16`)、`ASR-OUT-GROUP-COUNT`(9(7))、`ASR-OUT-TOTAL-ROWS`(9(10))

---

## 22-operations — 運用統括

API契約: [copy/api/ops-api.cpy](../../../subsystems/22-operations/copy/api/ops-api.cpy)（`OPB-*` / `OPF-*` / `OPR-*` / `OPD-*` の4契約）

二重起動防止に **flock**（`OPB-STATUS=02`=FLOCK-CONFLICT）。各ステップは冪等で、途中失敗時は `04`=HALTED で停止し再実行で続行可能。

### COBOLプログラム

| Program-ID | ファイル | 機能 |
|---|---|---|
| `OPS-BATCH-DAILY` | [src/ops-batch-daily.sqb](../../../subsystems/22-operations/src/ops-batch-daily.sqb) | 日次バッチ統括（検証→ソート→記帳→利息計算→振替→手数料→明細→連携→ファイナライズ） |
| `OPS-BATCH-MONTHLY` | [src/ops-batch-monthly.sqb](../../../subsystems/22-operations/src/ops-batch-monthly.sqb) | 月次バッチ統括（日次＋利息計上＋月次明細） |
| `OPS-FINALIZE` | [src/ops-finalize.sqb](../../../subsystems/22-operations/src/ops-finalize.sqb) | 記帳済取引の状態を `PT`→`SE`（settled）へチャンク単位で確定 |
| `OPS-PARTITION-ROLLOVER` | [src/ops-partition-rollover.cob](../../../subsystems/22-operations/src/ops-partition-rollover.cob) | 監査パーティションのロールオーバー（21-audit APR 呼出ラッパ） |
| `OPS-DRAIN-QUEUES` | [src/ops-drain-queues.cob](../../../subsystems/22-operations/src/ops-drain-queues.cob) | 連携OUTキューのドレイン（mock/real） |
| `OPS-SEED-AUDIT` | [src/ops-seed-audit.cob](../../../subsystems/22-operations/src/ops-seed-audit.cob) | 監査デモ用シードデータ投入 |
| `OPS-SEED-SYSTEM-ISAM` | [src/ops-seed-system-isam.cob](../../../subsystems/22-operations/src/ops-seed-system-isam.cob) | システム勘定 ISAM の初期化 |

### バッチ統括（OPB）

- 入力 `OPB-INPUT`: `OPB-BATCH-ID`(X(14))、`OPB-BUSINESS-DATE`(9(8))、`OPB-DRY-RUN`(`Y`/`N`)
- 出力 `OPB-OUTPUT`: `OPB-STATUS`(`00`=OK / `04`=HALTED / `08`=入力不正 / `02`=FLOCK-CONFLICT / `16`=致命的)、`OPB-OUT-LAST-STEP`(X(20))、`OPB-OUT-STEPS-RUN`(9(2))、`OPB-OUT-FINALIZED-COUNT`(9(7))、`OPB-OUT-DURATION-SEC`(9(5))

### ファイナライズ（OPF）

- 入力 `OPF-INPUT`: `OPF-BATCH-ID`、`OPF-BUSINESS-DATE`、`OPF-CHUNK-SIZE`(9(7))
- 出力 `OPF-OUTPUT`: `OPF-STATUS`(`00`/`08`/`12`/`16`)、`OPF-OUT-FINALIZED-COUNT`(9(7))、`OPF-OUT-CHUNKS-RUN`(9(4))
- `PT`→`SE` をチャンク分割で確定（大量更新のロック保持時間を抑制）。

### パーティションロールオーバー（OPR）

- 入力 `OPR-INPUT`: `OPR-OPERATOR-USER`、`OPR-RETENTION-DAYS`、`OPR-DRY-RUN`、`OPR-ENABLE-DETACH`
- 出力 `OPR-OUTPUT`: `OPR-STATUS`(`00`/`16`)、`OPR-OUT-CREATED-COUNT`、`OPR-OUT-DETACHED-COUNT`、`OPR-OUT-NEXT-PARTITION`

### キュードレイン（OPD）

- 入力 `OPD-INPUT`: `OPD-SOURCE-FILENAME`(X(120))、`OPD-MAX-RECORDS`(9(7))、`OPD-MODE`(`M`=mock/`R`=real)
- 出力 `OPD-OUTPUT`: `OPD-STATUS`(`00`/`04`=一部失敗/`16`)、`OPD-OUT-DRAINED-COUNT`、`OPD-OUT-FAILED-COUNT`

### シェルスクリプト（パイプライン各ステップ）

統括が呼び出す個別ステップ実行スクリプト:

| スクリプト | 対応サブシステム |
|---|---|
| [src/ops-step-13-iacr.sh](../../../subsystems/22-operations/src/ops-step-13-iacr.sh) | 13 利息計算 |
| [src/ops-step-14-ipst.sh](../../../subsystems/22-operations/src/ops-step-14-ipst.sh) | 14 利息計上 |
| [src/ops-step-15-ad.sh](../../../subsystems/22-operations/src/ops-step-15-ad.sh) | 15 口座振替 |
| [src/ops-step-16-fee.sh](../../../subsystems/22-operations/src/ops-step-16-fee.sh) | 16 手数料 |
| [src/ops-step-17-stmt.sh](../../../subsystems/22-operations/src/ops-step-17-stmt.sh) | 17 明細 |
| [src/ops-step-19-inti.sh](../../../subsystems/22-operations/src/ops-step-19-inti.sh) | 19 連携IN |
| [src/ops-step-20-drain.sh](../../../subsystems/22-operations/src/ops-step-20-drain.sh) | 20 連携OUT |

運用補助: [src/ops-batch-run-start.sh](../../../subsystems/22-operations/src/ops-batch-run-start.sh)、[src/ops-batch-run-complete.sh](../../../subsystems/22-operations/src/ops-batch-run-complete.sh)（`batch_run` 台帳の開始/完了記録）、[src/ops-master-load.sh](../../../subsystems/22-operations/src/ops-master-load.sh)（マスタ ISAM ロード）、[src/ops-scan-dormancy.sh](../../../subsystems/22-operations/src/ops-scan-dormancy.sh)（休眠スキャン起動）、[src/ops-seed-system-accounts.sh](../../../subsystems/22-operations/src/ops-seed-system-accounts.sh)（システム勘定投入）。

### スケジュール連携

systemd timer から起動される（詳細は [04-shared-infrastructure.md](04-shared-infrastructure.md)）:
- 日次バッチ: 毎日 23:00 JST
- 月次バッチ: 毎月1日 02:00 JST
- パーティションロールオーバー: 毎月25日 02:00 JST
- 自動振替リトライ: 毎月15日 04:00 JST
- 休眠スキャン: 毎週月曜 03:00 JST
