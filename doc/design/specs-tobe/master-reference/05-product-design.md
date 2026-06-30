# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `05-product` |
| ディレクトリ | [subsystems/05-product/](../../../subsystems/05-product/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/prod-api.cpy](../../../subsystems/05-product/copy/api/prod-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

商品コードをキーに商品マスタを参照し、商品名・種別（普通/当座/定期）・利息種別・当座貸越可否・預入期間・有効期間を返す。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 口座系 / 取引パイプライン / 金利・手数料参照 | 商品属性の参照 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `product.idx`（ISAM） / `products`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `PROD-LOAD` | [src/prod-load.cob](../../../subsystems/05-product/src/prod-load.cob) | シードを索引へロード | `MAIN-LOGIC` ほか |
| `PROD-LOOKUP` | [src/prod-lookup.cob](../../../subsystems/05-product/src/prod-lookup.cob) | 商品コードで参照 | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと（LOOKUP） ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[PROD-LOOKUP]
1. PRD-IN-CODE を主キーに索引を READ
2. 該当なし→04
3. 出力エリアへ商品属性をセット（種別・利息種別・当座貸越可否・期間・有効期間）
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 種別 `PRD-OUT-TYPE` | `S`=普通 / `C`=当座 / `T`=定期 |
| 2 | 入力不正コード | `08` は定義されていない |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照に成功 |
| `04` | 該当なし | 主キー一致レコードなし |
| `16` | 致命的エラー | I/O失敗等 |

> 注: `08`（入力不正）は本サブシステムでは定義されていない。

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引I/O失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/prod-api.cpy](../../../subsystems/05-product/copy/api/prod-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `PRD-IN-CODE` | `X(3)` | ✓ | 商品コード | 3桁 |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `product.idx` | 固定長 | `PRD-REC-CODE`(3) | 主キーのみ |
| 入力ファイル | [data/](../../../subsystems/05-product/data/) | 行順次 | — | LOAD 対象 |
| テーブル | `products` | PostgreSQL | 商品コード(3) | TO-BE |

### 3.3 前提・事前条件

- `product.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `PRD-OUT-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `PRD-OUT-CODE` | `X(3)` | 商品コード | 正常時 |
| `PRD-OUT-NAME` | `X(40)` | 商品名 | 正常時 |
| `PRD-OUT-TYPE` | `X(1)` | 種別 | `S`/`C`/`T` |
| `PRD-OUT-INTEREST-TYPE` | — | 利息種別 | 正常時 |
| `PRD-OUT-ALLOW-OVD` | — | 当座貸越可否 | 正常時 |
| `PRD-OUT-TERM-DAYS` | `9(4)` | 預入期間（日） | 正常時 |
| `PRD-OUT-EFF-FROM` | `9(8)` | 有効開始日 | 正常時 |
| `PRD-OUT-EFF-TO` | `9(8)` | 有効終了日 | 正常時 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `product.idx` | WRITE | 全レコード | LOAD 時のみ |

### 4.3 後続・事後条件

- 参照結果が口座開設・利息計算・手数料計算で利用される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-product.cpy](../../../subsystems/05-product/copy/private/fd-product.cpy)

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `PRD-REC-CODE` | `X(3)` | 主キー | 商品コード |
| `PRD-REC-NAME-KANJI` | `X(40)` | — | 商品名（漢字） |
| `PRD-REC-NAME-KANA` | `X(40)` | — | 商品名（カナ） |
| `PRD-REC-TYPE` | `X(1)` | — | 種別 S/C/T |
| `PRD-REC-INTEREST` | `X(1)` | — | 利息種別 |
| `PRD-REC-OVD` | `X(1)` | — | 当座貸越可否 |
| `PRD-REC-MIN-BAL` | `S9(15) COMP-3` | — | 最低残高 |
| `PRD-REC-TERM-DAYS` | `9(4)` | — | 預入期間 |
| `PRD-REC-EFF-FROM` | `9(8)` | — | 有効開始日 |
| `PRD-REC-EFF-TO` | `9(8)` | — | 有効終了日 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | `PRD-REC-MIN-BAL` | COMP-3 | NUMERIC | 整数（円）として保持 |
| 2 | API | CALL（prod-api.cpy） | `GET /api/v1/products/{productCode}` | architecture.md 準拠 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | 入力不正の扱い | TO-BE で 3桁検証（400）を追加するか検討 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
