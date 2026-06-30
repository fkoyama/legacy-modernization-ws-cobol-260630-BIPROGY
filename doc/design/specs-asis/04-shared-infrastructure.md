# AS-IS 機能仕様書 — 共有インフラ / DB / スケジューラ

> 全サブシステムが依存する共有コピーブック・ユーティリティ、PostgreSQL スキーマ（Flyway V1〜V7）、systemd スケジュール、保守コンソール、シードデータ生成。
> 共通規約は [00-overview.md](00-overview.md) を参照。

## 目次

1. [共有コピーブック（shared/copy）](#共有コピーブックsharedcopy)
2. [共有ユーティリティ（shared/util）](#共有ユーティリティsharedutil)
3. [データベーススキーマ（db/migration）](#データベーススキーマdbmigration)
4. [スケジューラ（systemd）](#スケジューラsystemd)
5. [保守コンソール（console）](#保守コンソールconsole)
6. [シードデータ生成（scripts/gen-seed）](#シードデータ生成scriptsgen-seed)

---

## 共有コピーブック（shared/copy）

| コピーブック | 用途 |
|---|---|
| [sqlca.cbl](../../../shared/copy/sqlca.cbl) | OCESQL の SQL 通信領域（SQLCODE/SQLSTATE/SQLERRD） |
| [std-header.cpy](../../../shared/copy/std-header.cpy) | プログラム標準ヘッダ（識別情報） |
| [ws-codes.cpy](../../../shared/copy/ws-codes.cpy) | 共通リターンコード `WS-RC-OK`(0)〜`WS-RC-FATAL`(16)、共通ステータス定数 |
| [ws-date.cpy](../../../shared/copy/ws-date.cpy) / [ws-date-validate.cpy](../../../shared/copy/ws-date-validate.cpy) / [ws-date-validate-procs.cpy](../../../shared/copy/ws-date-validate-procs.cpy) | 日付項目・妥当性検証ロジック（Y2K・うるう年・範囲） |
| [double-entry-helper.cpy](../../../shared/copy/double-entry-helper.cpy) / [double-entry-helper-procs.cpy](../../../shared/copy/double-entry-helper-procs.cpy) | 複式簿記記帳の共通手続（借方/貸方ペア INSERT、残高更新、借方=貸方検証） |
| [aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy) | 監査書込 API（`audit_outbox` への intent 投入インタフェース） |
| [aud-drain-state.cpy](../../../shared/copy/aud-drain-state.cpy) / [aud-drain-procs.cpy](../../../shared/copy/aud-drain-procs.cpy) | アウトボックス・ドレイン状態と手続（冪等転記、`event_key` 重複排除） |
| [ser-retry-state.cpy](../../../shared/copy/ser-retry-state.cpy) / [ser-retry-procs.cpy](../../../shared/copy/ser-retry-procs.cpy) | シリアライズ・リトライ FSM（SQLSTATE分類、指数バックオフ+ジッタ） |
| [shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) | 構造化ログ出力 API |
| [ebcdic-txn.cpy](../../../shared/copy/ebcdic-txn.cpy) | EBCDIC 連携入力レコード（H/D/T、800バイト固定長） |
| [ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy) | デコード済取引レコード（パイプライン中間形式） |

### リトライ FSM（ser-retry）

`CLASSIFY-SQL-RESULT` が SQLCODE/SQLSTATE を `OK`/`CONFLICT`(40001/40P01)/`DEFER`/`INDOUBT`/`FATAL` に分類。バックオフ `MIN(base×2^retry, cap) + jitter`。12-txnpost / 13〜16 の記帳系が共用。詳細は [02-transaction-pipeline.md](02-transaction-pipeline.md#12-txnpost--複式簿記記帳中核) を参照。

### 監査アウトボックス（aud-write / aud-drain）

記帳と同一の SERIALIZABLE トランザクションで `audit_outbox` に intent を INSERT し、`AUD-DRAIN` が `event_key`（MD5）で重複排除しつつ `audit_log` へ転記後 DELETE。少なくとも1回配信＋冪等で「ちょうど1回」を実現。

---

## 共有ユーティリティ（shared/util）

| ディレクトリ | 内容 |
|---|---|
| [aud-write/](../../../shared/util/aud-write/) | 監査書込ヘルパ実装・テスト |
| [aud-drain/](../../../shared/util/aud-drain/) | アウトボックス・ドレイン実装・テスト |
| [ser-retry（copy内）](../../../shared/copy/ser-retry-procs.cpy) | リトライ手続（copybook提供） |
| [shared-log/](../../../shared/util/shared-log/) | 構造化ログ実装 |
| [ebc-to-ascii/](../../../shared/util/ebc-to-ascii/) | EBCDIC(CP930)→ASCII/UTF-8 変換 C ライブラリ（19-integrationin が CALL） |
| [mq-publish/](../../../shared/util/mq-publish/) | RabbitMQ パブリッシュ補助（20-integrationout が利用） |
| [double-entry-helper-test/](../../../shared/util/double-entry-helper-test/) | 複式簿記ヘルパの単体テスト |

---

## データベーススキーマ（db/migration）

Flyway 形式のマイグレーション。バージョン順に適用。

| バージョン | ファイル | 内容 |
|---|---|---|
| V1 | [V1__initial_schema.sql](../../../db/migration/V1__initial_schema.sql) | 取引系テーブル（transactions / postings / balances / audit_log / interest_accruals / autodebit_schedules / batch_run） |
| V2 | [V2__master_pg_tables.sql](../../../db/migration/V2__master_pg_tables.sql) | マスタ系テーブル（accounts / customers / branches / products / calendar / interest_rates / fee_schedules） |
| V3 | [V3__audit_log_partitioning.sql](../../../db/migration/V3__audit_log_partitioning.sql) | audit_log の月次レンジパーティション化 |
| V4 | [V4__system_grants.sql](../../../db/migration/V4__system_grants.sql) | ロール `pb_app` / `pb_audit_writer` への権限付与 |
| V5 | [V5__audit_partition_functions.sql](../../../db/migration/V5__audit_partition_functions.sql) | パーティション作成/DETACH のストアド関数 |
| V6 | [V6__audit_event_key.sql](../../../db/migration/V6__audit_event_key.sql) | `event_key` 列・一意制約（冪等転記キー） |
| V7 | [V7__audit_outbox.sql](../../../db/migration/V7__audit_outbox.sql) | `audit_outbox` テーブル（トランザクショナル・アウトボックス） |

### 取引系テーブル（V1）

#### transactions（取引）
PK `txn_id` CHAR(18)。主要列: `business_date`、`category` CHAR(2)、`account_number` CHAR(13)、`counter_account_number`、`amount_jpy` BIGINT、`currency` CHAR(3)、`status` CHAR(2)、`reversal_of` CHAR(18)。
制約: `txn_amount_positive`(amount>0)、`txn_currency_jpy`(='JPY')、`txn_status_enum`('PT','SE','RV')、`txn_reversal_pair`((status='RV')=(reversal_of IS NOT NULL))。一意: `uq_txn_source_batch_seq`(source_batch_id, source_seq)、`uq_txn_reversal_of_when_rv`(reversal_of WHERE status='RV' = 1取引1取消)。

#### postings（仕訳明細）
PK `posting_id` CHAR(20)。`txn_id`、`line_no`、`account_number`、`debit_jpy`/`credit_jpy` BIGINT、`posting_role` CHAR(2)('DR'/'CR')。
制約: `pst_amounts_nonneg`、`pst_dr_xor_cr`((debit=0)<>(credit=0))、`pst_role_enum`、FK→transactions。一意: `uq_pst_txn_line`(txn_id, line_no)。

#### balances（残高）
PK `account_number`。`balance_jpy`、`available_jpy`、`hold_jpy`、`last_txn_id`、`last_business_date`。制約 `bal_hold_nonneg`。FOR UPDATE ロック対象。

#### audit_log（監査ログ）
PK `audit_id` BIGSERIAL。`business_date`、`subsystem`、`action`、`actor`、`target_type`、`target_id`、`payload_json` JSONB、`severity` CHAR(1)('I'/'W'/'E'/'C')、`schema_version`。V3で月次パーティション化。

#### interest_accruals（利息計算）
PK `accrual_id`。`business_date`、`account_number`、`product_code`、`principal_jpy`、`rate` NUMERIC(5,4)、`days`、`accrued_jpy`、`status` CHAR(2)('AC'/'PT'/'CN')、`posted_txn_id`。一意 `uq_iac_bd_acct`(business_date, account_number)。

#### autodebit_schedules（自動振替指図）
PK `instruction_id`。`payer_account`、`payee_name`、`amount_jpy`、`frequency` CHAR(1)('M'/'W'/'D')、`next_due_date`、`status` CHAR(2)('AC'/'SP'/'TM')、`last_attempt_result`、`consecutive_failures`。

#### batch_run（バッチ実行台帳）
PK `batch_id` CHAR(14)。`business_date`、`started_ts`、`completed_ts`、`status` CHAR(2)('RN'/'OK'/'FL'/'AB')、`current_step`、`last_failed_step`、`txns_posted`、`interest_accounts`、`errors_count`。

### マスタ系テーブル（V2）

| テーブル | PK | 主な列 |
|---|---|---|
| accounts | acct_number CHAR(13) | acct_name, branch_code, product_code, acct_status CHAR(1), cust_id, opened_date, dormancy_date |
| customers | cust_id CHAR(10) | cust_name, cust_name_kana, cust_status, tier, phone, address |
| branches | branch_code CHAR(3) | branch_name, branch_name_kana, branch_type, address, phone |
| products | product_code CHAR(3) | product_name, product_type, interest_eligible, fee_eligible, min_balance_jpy |
| calendar | cal_date | day_type CHAR(1), holiday_name, fiscal_year |
| interest_rates | (product_code, effective_date) | annual_rate NUMERIC(7,6), tier_threshold_jpy |
| fee_schedules | (category, tier, effective_date) | fee_jpy |

> マスタはランタイムでは ISAM ファイル（.idx）から参照される（[01-master-reference.md](01-master-reference.md)）。PostgreSQL 版は照会・連携・将来移行用。

### 監査アウトボックス（V7）

`audit_outbox`: `outbox_id` BIGSERIAL PK、`business_date`、`subsystem`、`action`、`actor`、`target_type`、`target_id`、`payload_json` JSONB、`severity`、`event_key` VARCHAR(80)。記帳と同一トランザクションで INSERT、`AUD-DRAIN` が `event_key`（V6 一意制約）で冪等転記後 DELETE。権限は `pb_app` に SELECT/INSERT/DELETE。

---

## スケジューラ（systemd）

[systemd/install.sh](../../../systemd/install.sh) でユニットをインストール。全タイマは Asia/Tokyo。

| サービス / タイマ | スケジュール | 起動内容 |
|---|---|---|
| [practice-bank-batch-daily](../../../systemd/practice-bank-batch-daily.service) / [.timer](../../../systemd/practice-bank-batch-daily.timer) | 毎日 23:00 | OPS-BATCH-DAILY |
| [practice-bank-batch-monthly](../../../systemd/practice-bank-batch-monthly.service) / [.timer](../../../systemd/practice-bank-batch-monthly.timer) | 毎月1日 02:00 | OPS-BATCH-MONTHLY |
| [practice-bank-partition-rollover](../../../systemd/practice-bank-partition-rollover.service) / [.timer](../../../systemd/practice-bank-partition-rollover.timer) | 毎月25日 02:00 | OPS-PARTITION-ROLLOVER |
| [practice-bank-autodebit-retry](../../../systemd/practice-bank-autodebit-retry.service) / [.timer](../../../systemd/practice-bank-autodebit-retry.timer) | 毎月15日 04:00 | 自動振替リトライ（15-autodebit） |
| [practice-bank-dormancy-scan](../../../systemd/practice-bank-dormancy-scan.service) / [.timer](../../../systemd/practice-bank-dormancy-scan.timer) | 毎週月曜 03:00 | 休眠スキャン（09-accountlifecycle DORMANCY-SCAN） |

二重起動は OPS 側の flock で防止（[03-operations-audit.md](03-operations-audit.md)）。

---

## 保守コンソール（console）

オペレータ向け対話メニュー・デモ実行。ラベルは多言語（[msg/labels-ja.txt](../../../console/msg/labels-ja.txt) / [labels-en.txt](../../../console/msg/labels-en.txt)）。

| Program / Script | ファイル | 機能 |
|---|---|---|
| `OPER-CONSOLE` | [src/oper-console.sqb](../../../console/src/oper-console.sqb) | オペレータコンソール（照会・運用メニュー） |
| `CON-AUDIT-DEMO` | [src/con-audit-demo.cob](../../../console/src/con-audit-demo.cob) | 監査機能デモ |
| `CON-REVERSE` | [src/con-reverse.cob](../../../console/src/con-reverse.cob) | 取引取消の対話実行（12-txnpost REVERSE 呼出） |
| `CON-POST-GEN` | [src/con-post-gen.sqb](../../../console/src/con-post-gen.sqb) | 記帳データ生成（デモ・テスト用） |

実行スクリプト: [scripts/run-scenario.sh](../../../console/scripts/run-scenario.sh)、[run-stage.sh](../../../console/scripts/run-stage.sh)、[run-post.sh](../../../console/scripts/run-post.sh)、[run-reverse.sh](../../../console/scripts/run-reverse.sh)、[seed-audit-demo.sh](../../../console/scripts/seed-audit-demo.sh)、[seed-batch-demo.sh](../../../console/scripts/seed-batch-demo.sh)。テスト: [tests/console-test.sh](../../../console/tests/console-test.sh)。

---

## シードデータ生成（scripts/gen-seed）

マスタ ISAM・テスト用データを生成する Python スクリプト群。一括実行は [gen-all-seed.sh](../../../scripts/gen-seed/gen-all-seed.sh)。

| スクリプト | 生成対象 |
|---|---|
| [gen-branches-seed.py](../../../scripts/gen-seed/gen-branches-seed.py) | 支店マスタ |
| [gen-customers-seed.py](../../../scripts/gen-seed/gen-customers-seed.py) | 顧客マスタ |
| [gen-accounts-seed.py](../../../scripts/gen-seed/gen-accounts-seed.py) | 口座マスタ |
| [gen-products-seed.py](../../../scripts/gen-seed/gen-products-seed.py) | 商品マスタ |
| [gen-calendar-seed.py](../../../scripts/gen-seed/gen-calendar-seed.py) | 営業日カレンダー |
| [gen-interestrates-seed.py](../../../scripts/gen-seed/gen-interestrates-seed.py) | 金利マスタ |
| [gen-feeschedules-seed.py](../../../scripts/gen-seed/gen-feeschedules-seed.py) | 手数料体系 |

生成物は [data/master/](../../../data/master/) / [data/staging/](../../../data/staging/) / [data/in/](../../../data/in/) に配置され、ISAM ロード（ops-master-load.sh）で各サブシステムが参照する `.idx` になる。
