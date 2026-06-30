# アーキテクチャ構成（ハッカソン版）

## 1. 方針

マスタ参照系（サブシステム 01〜09）と取引パイプライン（サブシステム 10〜20）を、
2つのSpring Bootアプリに分離する。

- Master Reference App: マスタ参照APIを提供
- Transaction Pipeline App: 取引処理を担当し、必要なマスタをHTTPで取得

ハッカソン向けのため、構成は最小限にする。

## 2. システム分割

### 2.1 Master Reference App（01〜09）

- 01-calendar
- 02-branch
- 03-customer
- 04-customersearch
- 05-product
- 06-interestrate
- 07-feeschedule
- 08-account
- 09-accountlifecycle

役割: マスタデータの参照APIを返す

### 2.2 Transaction Pipeline App（10〜20）

- 10-txnvalidate
- 11-txnsortmerge
- 12-txnpost
- 13-interestaccrual
- 14-interestpost
- 15-autodebit
- 16-fee
- 17-statement
- 18-inquiry
- 19-integrationin
- 20-integrationout

役割: 取引処理を実行し、必要なマスタをHTTPで参照する
Javaコンソールアプリとする

## 3. 連携方式

- 方式: 同期HTTP（REST/JSON）
- 方向: Transaction Pipeline App -> Master Reference App
- 目的: 取引処理に必要なマスタ情報（顧客、口座、商品、金利、手数料、営業日）を取得

代表API（例）:

- GET /api/v1/customers/{customerId}
- GET /api/v1/accounts/{accountNo}
- GET /api/v1/products/{productCode}
- GET /api/v1/interest-rates/{rateCode}
- GET /api/v1/fee-schedules/{feeCode}
- GET /api/v1/business-calendar/{date}

## 4. アプリ構成（最小）

両アプリとも同じシンプル構成とする。

- Controller
- Service
- Repository

各アプリは1つのPostgreSQLを使う（合計2DBでも、1DB内スキーマ分離でも可）。

## 5. 処理フロー

1. Transaction Pipeline App が取引リクエストを受ける
2. 必要に応じて Master Reference App のAPIを呼ぶ
3. 取引検証・記帳などを実行する
4. 結果を返す

## 6. 技術スタック

- Java
- Spring Boot
- PostgreSQL

## 7. RDBMS移行テーブル一覧（所属つき）

方針: 現在PostgreSQLにあるテーブル + ISAM（INDEXED）で管理しているデータを、すべてRDBMSテーブルとして管理する。

ISAMでキャッシュ化・高速化する処理については、インメモリキャッシュで対応する。

### 7.1 マスター管理アプリに属するテーブル

| テーブル名 | 現状 | 元データ | 所属 | 備考 |
|---|---|---|---|---|
| calendar | 既存 | PostgreSQL + CALENDAR-FILE(ISAM) | Master Reference App | 現行継続 |
| branches | 既存 | PostgreSQL + BRANCH-FILE(ISAM) | Master Reference App | region/opened_date/status を保持 |
| customers | 既存 | PostgreSQL + CUSTOMER-FILE(ISAM) | Master Reference App | opened_date を追加保持 |
| products | 既存 | PostgreSQL + PRODUCT-FILE(ISAM) | Master Reference App | name_kana/ovd/term_days/eff期間の反映を検討 |
| interest_rates | 既存 | PostgreSQL + IRATE-FILE(ISAM) | Master Reference App | tier/tier_min/tier_max/eff_to の反映を検討 |
| fee_schedules | 既存 | PostgreSQL + FS-FILE(ISAM) | Master Reference App | tier_min/tier_max/eff_to の反映を検討 |
| accounts | 既存 | PostgreSQL + ACCOUNT-FILE(ISAM) | Master Reference App | closed_date/overdraft/term_days の反映を検討 |

補足:

- 04-customersearch と 09-accountlifecycle は上記マスタテーブルを参照/更新するアプリ機能として扱う（専用テーブルは必須ではない）

### 7.2 取引パイプラインに属するテーブル

| テーブル名 | 現状 | 元データ | 所属 | 備考 |
|---|---|---|---|---|
| transactions | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| postings | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| balances | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| interest_accruals | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| autodebit_schedules | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| batch_run | 既存 | PostgreSQL | Transaction Pipeline App | 現行継続 |
| audit_log | 既存 | PostgreSQL | Transaction Pipeline App | 監査テーブル（パーティション運用） |
| audit_outbox | 既存 | PostgreSQL | Transaction Pipeline App | 連携イベント出力 |

### 7.3 ISAMからRDBMSへの対応関係（要約）

| ISAMファイル | 移行先RDBMSテーブル | 所属 |
|---|---|---|
| CALENDAR-FILE | calendar | Master Reference App |
| BRANCH-FILE | branches | Master Reference App |
| CUSTOMER-FILE | customers | Master Reference App |
| PRODUCT-FILE | products | Master Reference App |
| IRATE-FILE | interest_rates | Master Reference App |
| FS-FILE | fee_schedules | Master Reference App |
| ACCOUNT-FILE | accounts | Master Reference App |

### 7.4 結論

- ISAM管理データはすべて Master Reference App 側RDBMSに集約できる
- 取引パイプラインは取引系テーブルのみ保持し、マスタはHTTP API経由で参照する

