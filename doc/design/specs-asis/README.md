# AS-IS 機能仕様書（practice-bank）

> 練習用リテール銀行バッチシステム **practice-bank**（GnuCOBOL + OCESQL / PostgreSQL / ISAM / RabbitMQ）の現行（AS-IS）機能仕様書一式。
> 全22サブシステム＋共有インフラを、コード位置（ファイル・PROGRAM-ID・段落・項目名・ステータスコード）まで追跡可能な形で記述する。

## ドキュメント構成

| # | ドキュメント | 範囲 |
|---|---|---|
| 00 | [システム概要・共通規約](00-overview.md) | 3層アーキ、ドメインモデル、口座番号体系、区分/状態コード、リターンコード、不変条件(I1〜I5)、堅牢性機能 |
| 01 | [マスタ参照系（01〜09）](01-master-reference.md) | カレンダー・支店・顧客・顧客検索・商品・金利・手数料・口座・口座ライフサイクル |
| 02 | [取引パイプライン（10〜20）](02-transaction-pipeline.md) | 検証・ソート/突合・**記帳（複式簿記）**・利息計算/計上・自動振替・手数料・明細・照会・連携IN/OUT |
| 03 | [運用・監査（21〜22）](03-operations-audit.md) | 監査ログ照会/保全/パーティション、バッチ統括・ファイナライズ・運用ジョブ |
| 04 | [共有インフラ / DB / スケジューラ](04-shared-infrastructure.md) | 共有コピーブック・ユーティリティ、PostgreSQLスキーマ(V1〜V7)、systemdタイマ、保守コンソール、シード生成 |

## サブシステム早見表

| # | サブシステム | 層 | 主プログラム | データ |
|---|---|---|---|---|
| 01 | calendar | マスタ | CAL-LOOKUP/NEXT-BD/PREV-BD | ISAM |
| 02 | branch | マスタ | BRANCH-LOOKUP | ISAM |
| 03 | customer | マスタ | CUST-LOOKUP/STATUS-CHANGE | ISAM |
| 04 | customersearch | マスタ | CSRCH-AND/BY-ADDRESS/LIST-PAGED | ISAM |
| 05 | product | マスタ | PROD-LOOKUP | ISAM |
| 06 | interestrate | マスタ | IRATE-LOOKUP | ISAM |
| 07 | feeschedule | マスタ | FEE-SCHED-LOOKUP | ISAM |
| 08 | account | マスタ | ACCT-LOOKUP/EXISTS/UPDATE-DORMANCY | ISAM |
| 09 | accountlifecycle | マスタ | ALC-OPEN/CHANGE-STATE/DORMANCY-SCAN | ISAM |
| 10 | txnvalidate | 取引 | TXVAL-VALIDATE-BATCH | ファイル |
| 11 | txnsortmerge | 取引 | TXSM-SORT/MERGE-BATCH | ファイル |
| 12 | **txnpost** | 取引 | **TXPOST-RUN-BATCH/REVERSE** | **PostgreSQL** |
| 13 | interestaccrual | 取引 | IACR-RUN-DAILY | PostgreSQL |
| 14 | interestpost | 取引 | IPST-RUN-MONTHEND | PostgreSQL |
| 15 | autodebit | 取引 | AD-RUN-DAILY | PostgreSQL |
| 16 | fee | 取引 | FEE-CHARGE | PostgreSQL |
| 17 | statement | 取引 | STMT-GENERATE-BATCH | PostgreSQL→出力 |
| 18 | inquiry | 取引 | INQ-MAIN | PostgreSQL（読取） |
| 19 | integrationin | 取引 | INTI-DECODE-BATCH | ファイル |
| 20 | integrationout | 取引 | INTO-PUBLISH-EVENT/DRAIN | RabbitMQ |
| 21 | audit | 運用 | AUDIT-QUERY-FORENSIC 他 | PostgreSQL |
| 22 | operations | 運用 | OPS-BATCH-DAILY/MONTHLY 他 | 統括 |

## 凡例・記法

- **ステータスコード**: `00`=正常 / `02`=flock競合 / `04`=一部却下・HALTED / `08`=入力不正 / `12`=I/O障害 / `16`=致命的
- **内部RC**: `WS-RC-OK`(0) / `WS-RC-WARN`(4) / `WS-RC-ERROR`(8) / `WS-RC-IO`(12) / `WS-RC-FATAL`(16)
- **不変条件**: I1=冪等性 / I3=残高保全 / I4=業務日単調性 / I5=禁止操作（詳細は [00-overview.md](00-overview.md)）
- ファイルリンクは workspace ルートからの相対パス（本フォルダは `doc/design/specs-asis/`）。

## 関連ドキュメント

- [doc/work/modernization-brief.md](../../work/modernization-brief.md) — モダナイゼーション方針
- [doc/design/](../) — 詳細設計テンプレート・Azureインフラ設計
