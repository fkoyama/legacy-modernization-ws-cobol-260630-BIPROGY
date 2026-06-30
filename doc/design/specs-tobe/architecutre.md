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

