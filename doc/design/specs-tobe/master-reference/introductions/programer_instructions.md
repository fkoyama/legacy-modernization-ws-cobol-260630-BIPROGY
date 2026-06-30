# Master Reference App 実装手順書（Programmer Instructions）

本書は `doc/design/specs-tobe/master-reference/` の設計書群を、
[modernized-app/master_reference_app/](../../../../../modernized-app/master_reference_app/) に実装するための開発者向け手順書である。

- 全体概要: [doc/design/specs-tobe/architecutre.md](../../architecutre.md)
- API契約: [master-reference-openapi.yaml](../master-reference-openapi.yaml)
- 設計書: [master-reference/](../) 配下の `01`〜`09`
- SQL/スキーマ: [db/migration/](../../../../../db/migration/)

---

## 1. 目的とゴール

マスタ参照系サブシステム（01〜09）を、1つの Spring Boot アプリ（Master Reference App）として実装する。
Transaction Pipeline App（10〜20）から同期 HTTP（REST/JSON）で参照される。

完成の定義（Definition of Done）:

1. [master-reference-openapi.yaml](../master-reference-openapi.yaml) の全エンドポイントが実装されている
2. データは `db/migration` のテーブルを参照する（独自スキーマを作らない）
3. COBOL ステータス → HTTP 変換ルール（後述）に準拠している
4. `mvn test` がグリーン、`mvn package` が成功する
5. `/actuator/health` が `UP` を返す

---

## 2. 技術スタック・前提

| 項目 | 内容 |
|---|---|
| 言語 | Java（設計推奨 21 / 当環境は 17。[pom.xml](../../../../../modernized-app/master_reference_app/pom.xml) の `java.version` で調整） |
| フレームワーク | Spring Boot 3.x（Web / Validation / JDBC / Actuator） |
| DBアクセス | Spring JDBC（`JdbcTemplate` / `NamedParameterJdbcTemplate`） |
| DB | PostgreSQL |
| マイグレーション | Flyway（`db/migration` を利用） |
| ビルド | Maven（`mvnw` 同梱） |

方針: ハッカソン版のため構成は最小限。Controller / Service / Repository の3層に限定し、過度な抽象化はしない。

---

## 3. プロジェクト構成（パッケージ方針）

ベースパッケージ: `com.practicebank.masterreference`

サブシステム単位（feature-based）でパッケージを分割する。

```
com.practicebank.masterreference
├─ MasterReferenceApplication.java        # 既存
├─ common/                                # 共通（エラー、ステータス変換、例外）
│   ├─ ApiError.java                       # エラーレスポンスDTO
│   ├─ ErrorResponseFactory.java
│   ├─ NotFoundException.java
│   ├─ BadRequestException.java
│   └─ GlobalExceptionHandler.java         # @RestControllerAdvice
├─ calendar/                              # 01-calendar
│   ├─ CalendarController.java
│   ├─ CalendarService.java
│   ├─ CalendarRepository.java
│   └─ dto/...
├─ branch/                                # 02-branch
├─ customer/                              # 03-customer
├─ customersearch/                        # 04-customersearch（自前テーブルなし、customer参照）
├─ product/                               # 05-product
├─ interestrate/                          # 06-interestrate
├─ feeschedule/                           # 07-feeschedule
├─ account/                               # 08-account
└─ accountlifecycle/                      # 09-accountlifecycle（accounts参照/更新）
```

各featureパッケージは原則:

- `XxxController`（HTTP受信/応答、入力バリデーション）
- `XxxService`（業務ルール、トランザクション）
- `XxxRepository`（`JdbcTemplate` によるSQL）
- `dto/`（リクエスト/レスポンスのレコード）

---

## 4. API ベースパスとポート

- 全エンドポイントは `/api/v1` 配下（OpenAPI 準拠）。
  - 実装では `application.yml` に `server.servlet.context-path: /api/v1` を設定するか、各 `@RequestMapping("/api/v1/...")` に付与する。
- ポートは [application.yml](../../../../../modernized-app/master_reference_app/src/main/resources/application.yml) の `server.port`（現状 8081）を使用。

---

## 5. DB / Flyway 方針（db/migration を活用）

- マスタテーブルは既存DDLを正とする: [V2__master_pg_tables.sql](../../../../../db/migration/V2__master_pg_tables.sql)
- アプリ起動時に Flyway で `db/migration` を適用する。
- 現状 [application.yml](../../../../../modernized-app/master_reference_app/src/main/resources/application.yml) は `spring.flyway.locations: classpath:db/migration` を指定している。
  リポジトリ直下の `db/migration` を使う場合は、次のいずれかで対応する:
  1. ビルド時に `db/migration/*.sql` を `src/main/resources/db/migration/` へコピーする（推奨・自己完結）
  2. `spring.flyway.locations` を `filesystem:../../db/migration` に変更する（実行ディレクトリ依存）
- 新規テーブルは作らない。属性拡張が必要な場合のみ、新しいバージョン（`V8__...`）を追加する。

参照する主なテーブル:

| サブシステム | テーブル |
|---|---|
| 01-calendar | `calendar` |
| 02-branch | `branches` |
| 03-customer / 04-customersearch | `customers` |
| 05-product | `products` |
| 06-interestrate | `interest_rates` |
| 07-feeschedule | `fee_schedules` |
| 08-account / 09-accountlifecycle | `accounts` |

カラム定義は [V2__master_pg_tables.sql](../../../../../db/migration/V2__master_pg_tables.sql) を参照。

---

## 6. COBOL ステータス → HTTP 変換（共通ルール）

OpenAPI の定義に従い、Service 層は COBOL 戻り値に対応する結果を返し、Controller / 例外ハンドラで HTTP に変換する。

| COBOL status | 意味 | HTTP | 実装方針 |
|---|---|---|---|
| `00` | 正常 | 200 / 201 / 204 | 正常応答（新規作成は 201） |
| `02` | 警告（重複検知等） | 200 | 本文に `warning=true` を設定 |
| `04` | 該当なし | 404 | `NotFoundException` |
| `08` | 入力不正 | 400 | `BadRequestException` / Bean Validation |
| `10` | EOF | 200 | 空配列 `[]` を返す |
| `12` / `16` | I/O・致命的 | 500 | 例外 → 500（共通ハンドラ） |

エラーレスポンスは共通形式（`ApiError`）で返す。`GlobalExceptionHandler`（`@RestControllerAdvice`）に集約する。

---

## 7. 共通実装方針

- バリデーション: `@Valid` + Bean Validation（`@Pattern`, `@Size`, `@Min`/`@Max` 等）。パス/クエリ制約は OpenAPI の `pattern`/`minimum`/`maximum` に一致させる。
- DTO: Java `record` を基本とする（不変・簡潔）。
- 日付: `LocalDate` を使用。カレンダーの対象範囲は `20260101`–`20301231`、範囲外は 400。
- 金額: 円は整数（`long` / `BIGINT`）。金利は OpenAPI に従いマイクロ単位（×1,000,000）で返す。
- キャッシュ: ISAM でのキャッシュ高速化は「インメモリキャッシュ」で代替（architecutre.md 方針）。
  - 例: カレンダー参照は `@Cacheable` もしくは起動時ロードのオンメモリ Map。まずは未導入でも可、必要に応じて追加。
- トランザクション: 更新系（顧客状態変更・口座開設・状態遷移・休眠日更新・休眠スキャン）は `@Transactional`。
- 監査: 設計上 21-audit へ記録する更新系がある。ハッカソン版では最小実装（ログ出力 or `audit_log` への INSERT）でよい。段階導入。

---

## 8. サブシステム別 実装ガイド

各サブシステムの設計書とエンドポイントの対応。詳細な業務ルール・戻り値は各設計書を参照する。

### 8.1 01-calendar — [設計](../01-calendar-design.md)
- `GET /business-calendar/{date}` 営業日区分（B/H/W）
- `GET /business-calendar/{date}/next-business-day` 翌営業日（最大10日先）
- `GET /business-calendar/{date}/previous-business-day` 前営業日（最大10日前）
- テーブル: `calendar`。範囲外日付は 400、探索内に営業日なしは 404。

### 8.2 02-branch — [設計](../02-branch-design.md)
- `GET /branches`（`region` 任意で地域別）
- `GET /branches/{branchCode}`（3桁）
- テーブル: `branches`。0件は空配列200、単件未存在は404。

### 8.3 03-customer — [設計](../03-customer-design.md)
- `GET /customers`（`kana` 前方一致 / `phone` 完全一致 / 全件、カーソル `startAfter`）
- `GET /customers/{customerId}`（10桁）
- `PATCH /customers/{customerId}/status`（状態変更＋監査）
- テーブル: `customers`。状態変更は `@Transactional`。

### 8.4 04-customersearch — [設計](../04-customersearch-design.md)
- `GET /customer-search`（`kanaPrefix` AND `phonePrefix` / `addressSubstr` / ページング）
- 自前テーブルなし。`customers` を検索する。

### 8.5 05-product — [設計](../05-product-design.md)
- `GET /products/{productCode}`（3桁）
- テーブル: `products`。未存在は404。

### 8.6 06-interestrate — [設計](../06-interestrate-design.md)
- `GET /interest-rates?productCode&tier&effectiveDate`
- テーブル: `interest_rates`。レートはマイクロ単位で返却。

### 8.7 07-feeschedule — [設計](../07-feeschedule-design.md)
- `GET /fee-schedules?category&tier&effectiveDate`（category=10/20/30/40）
- テーブル: `fee_schedules`。

### 8.8 08-account — [設計](../08-account-design.md)
- `GET /accounts?customerId`（顧客の口座一覧、最大20件・カーソル、重複は warning=true）
- `GET /accounts/{accountNo}`（13桁）
- `GET /accounts/{accountNo}/exists`（存在・状態・有効フラグ。未存在も200で found=false）
- `PATCH /accounts/{accountNo}/dormancy-date`（A/D かつ 新日≥旧日のみ更新、NOOPは200）
- テーブル: `accounts`。

### 8.9 09-accountlifecycle — [設計](../09-accountlifecycle-design.md)
- `POST /accounts`（開設 ALC-OPEN、採番、初期状態 P、201）
- `POST /accounts/{accountNo}/state-transitions`（AC/CN/SU/LS/CL/FC、SU/FC は reason 必須）
- `POST /account-lifecycle/dormancy-scan`（基準日=業務日-730日、A→D。候補なし404）
- `POST /account-lifecycle/reactivation-scan`（スタブ、常に404）
- テーブル: `accounts`。更新系は `@Transactional`＋監査。

---

## 9. 実装順序（マイルストーン）

1. 共通基盤: `common`（`ApiError` / 例外 / `GlobalExceptionHandler`）と Flyway 適用、`/actuator/health` 確認
2. 参照系（読み取りのみ・単純）: 05-product → 02-branch → 01-calendar → 06-interestrate → 07-feeschedule
3. 顧客系: 03-customer（参照）→ 04-customersearch
4. 口座参照: 08-account（GET 系）
5. 更新系: 03-customer 状態変更 → 09-accountlifecycle（開設・遷移・休眠日・スキャン）
6. 仕上げ: 監査記録の追加、インメモリキャッシュ、整備

各ステップ完了時に対応エンドポイントの単体テストを追加する。

---

## 10. テスト方針

- 単体: Service / Repository を中心に検証。Repository は Testcontainers もしくはローカル PostgreSQL を使用。
- API: `@WebMvcTest` でController層（バリデーション・ステータス変換）を検証。
- 既存の `MasterReferenceApplicationTests`（contextLoads）は DB 接続が必要なため、CI ではプロファイルで切り替える。
- 受け入れ: OpenAPI の各 example レスポンスと整合する形状を返すこと。

---

## 11. コーディング規約（最小）

- DTO は `record`、フィールド名は OpenAPI のプロパティ名（camelCase）に一致させる。
- SQL は Repository に集約し、文字列連結でのSQL組み立て（インジェクション）を避け、必ずプレースホルダを使う。
- 例外は `common` の `NotFoundException` / `BadRequestException` を投げ、Controller で try/catch しない。
- マジックナンバー（範囲 20260101–20301231、休眠 730 日、最大10日探索 等）は定数化する。
