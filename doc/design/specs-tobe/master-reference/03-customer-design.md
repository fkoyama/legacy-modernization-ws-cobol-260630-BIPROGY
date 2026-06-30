# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `03-customer` |
| ディレクトリ | [subsystems/03-customer/](../../../subsystems/03-customer/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/cust-api.cpy](../../../subsystems/03-customer/copy/api/cust-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

顧客IDをキーに顧客マスタを参照し、カナ前方一致・電話完全一致による検索、全件順次取得、状態変更（監査記録付き）を提供する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 04-customersearch / 口座系 / 取引パイプライン | 顧客参照・検索 |
| 下流（呼び出し先） | 21-audit（AUD-WRITE） | 状態変更の監査記録（action=`CUST_STATUS_CHANGED`） |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `customer.idx`（ISAM） / `customers`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `CUST-LOAD` | [src/cust-load.cob](../../../subsystems/03-customer/src/cust-load.cob) | 3キー索引へロード | `MAIN-LOGIC` ほか |
| `CUST-LOOKUP` | [src/cust-lookup.cob](../../../subsystems/03-customer/src/cust-lookup.cob) | 顧客IDで参照 | `MAIN-LOGIC` |
| `CUST-LIST-ALL` | [src/cust-list-all.cob](../../../subsystems/03-customer/src/cust-list-all.cob) | 全件順次 | `MAIN-LOGIC` |
| `CUST-SEARCH-BY-KANA` | [src/cust-search-by-kana.cob](../../../subsystems/03-customer/src/cust-search-by-kana.cob) | カナ前方一致（副キー `CR-KANA`） | `MAIN-LOGIC` |
| `CUST-SEARCH-BY-PHONE` | [src/cust-search-by-phone.cob](../../../subsystems/03-customer/src/cust-search-by-phone.cob) | 電話完全一致（副キー `CR-PHONE`、`START KEY =`） | `MAIN-LOGIC` |
| `CUST-STATUS-CHANGE` | [src/cust-status-change.cob](../../../subsystems/03-customer/src/cust-status-change.cob) | 状態変更＋監査記録 | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可。状態変更は監査記録を伴う更新（排他に注意） |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[CUST-LOOKUP]（CUST-IN-OP='L'）
1. CUST-IN-ID を主キーに索引を READ
2. 該当なし→04
3. 出力エリアへ顧客属性をセット（CUST-OUT-OPENED もここで設定）

[CUST-SEARCH-BY-KANA]（'K'）
1. 副キー CR-KANA で START（前方一致）→ READ NEXT 反復

[CUST-SEARCH-BY-PHONE]（'P'）
1. 副キー CR-PHONE で START KEY =（完全一致）→ READ

[CUST-STATUS-CHANGE]
1. 顧客を READ → 状態を REWRITE
2. 21-audit へ action=CUST_STATUS_CHANGED を記録
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 操作区分 `CUST-IN-OP` | `L`=参照 / `K`=カナ検索 / `P`=電話検索 / `A`=全件 / ` `=継続 |
| 2 | 電話検索 | `START KEY =` による完全一致（前方一致ではない） |
| 3 | `CUST-OUT-OPENED` | `CUST-LOOKUP` のみ設定。一覧/検索系では未設定 |
| 4 | 状態変更 | 必ず監査記録を伴う |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照・検索・更新に成功 |
| `04` | 該当なし | キー一致レコードなし |
| `08` | 入力不正 | 入力パラメータ不正 |
| `10` | EOF | 一覧/検索反復の終端 |
| `16` | 致命的エラー | I/O失敗等 |

### 2.4 排他・トランザクション制御

`CUST-STATUS-CHANGE` は顧客レコードの REWRITE と監査記録をセットで実施。TO-BE では PG トランザクション境界内で監査アウトボックスへ記録する想定。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引I/O失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |
| 状態変更 | 監査記録 | [shared/copy/aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/cust-api.cpy](../../../subsystems/03-customer/copy/api/cust-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `CUST-IN-ID` | `9(10)` | △ | 顧客ID | `L` 時必須 |
| `CUST-IN-KANA` | `X(50)` | △ | カナ（前方一致） | `K` 時必須 |
| `CUST-IN-PHONE` | `X(15)` | △ | 電話（完全一致） | `P` 時必須 |
| `CUST-IN-OP` | `X(1)` | ✓ | 操作区分 | `L`/`K`/`P`/`A`/` ` |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `customer.idx` | 固定長 | `CR-ID`(9(10)) | 副キー `CR-KANA`, `CR-PHONE` |
| 入力ファイル | [data/](../../../subsystems/03-customer/data/) | 行順次 | — | LOAD 対象 |
| テーブル | `customers` | PostgreSQL | 顧客ID(10) | TO-BE |

### 3.3 前提・事前条件

- `customer.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `CUST-OUT-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `CUST-OUT-ID` | `9(10)` | 顧客ID | 正常時 |
| `CUST-OUT-KANA` | `X(50)` | カナ氏名 | 正常時 |
| `CUST-OUT-KANJI` | `X(60)` | 漢字氏名 | 正常時 |
| `CUST-OUT-PHONE` | `X(15)` | 電話番号 | 正常時 |
| `CUST-OUT-ADDRESS` | `X(200)` | 住所 | 正常時 |
| `CUST-OUT-OPENED` | `9(8)` | 開設日 | `CUST-LOOKUP` のみ設定 |
| `CUST-OUT-STATUS-CODE` | `X(1)` | 顧客状態 | 正常時 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `customer.idx` | REWRITE | 状態（`CR-STATUS`） | CUST-STATUS-CHANGE |
| 監査ログ | 21-audit | 記録 | action=`CUST_STATUS_CHANGED` | 状態変更時 |

### 4.3 後続・事後条件

- 状態変更時、監査アウトボックスに1件のイベントが登録される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-customer.cpy](../../../subsystems/03-customer/copy/private/fd-customer.cpy)

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `CR-ID` | `9(10)` | 主キー | 顧客ID |
| `CR-KANA` | `X(50)` | 副キー | カナ氏名 |
| `CR-KANJI` | `X(60)` | — | 漢字氏名 |
| `CR-PHONE` | `X(15)` | 副キー | 電話番号 |
| `CR-ADDRESS` | `X(200)` | — | 住所 |
| `CR-OPENED-DATE` | `9(8)` | — | 開設日 |
| `CR-STATUS` | `X(1)` | — | 状態 |
| `CR-CREATED-TS` | `9(14)` | — | 作成タイムスタンプ |
| `CR-UPDATED-TS` | `9(14)` | — | 更新タイムスタンプ |
| `CR-TIER` | `X(1)` | — | 顧客ティア |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | `CR-OPENED-DATE` | あり | PG `customers` に相当列なし | スコープ外（読み取りはPG項目のみ） |
| 2 | `tier`/`address`/`phone` | ISAM桁数 | PG側で桁数が異なる | [04-shared-infrastructure.md](../specs-asis/04-shared-infrastructure.md) §DBスキーマ参照 |
| 3 | 電話検索 | `START KEY =` 完全一致 | SQL `WHERE phone = ?` | 完全一致を維持 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | `CUST-OUT-OPENED` の扱い | PGに相当列がないため一覧/検索同様に未設定とするか確認 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
