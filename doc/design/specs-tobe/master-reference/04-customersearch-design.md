# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `04-customersearch` |
| ディレクトリ | [subsystems/04-customersearch/](../../../subsystems/04-customersearch/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/csrch-api.cpy](../../../subsystems/04-customersearch/copy/api/csrch-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

顧客マスタに対する複合検索を提供する。カナ前方一致 AND 電話前方一致の積集合、住所部分一致、ページング一覧を実現する。自前データは持たず、03-customer の索引を利用する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 照会系 / 運用 | 顧客の複合検索 |
| 下流（呼び出し先） | 03-customer 索引 | 検索対象データの参照 |
| 参照データ | `customer.idx`（ISAM、03の索引を共用） | 自前データなし |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `CSRCH-AND` | [src/csrch-and.cob](../../../subsystems/04-customersearch/src/csrch-and.cob) | カナ前方一致 AND 電話前方一致の積集合（各最大200件→INTERSECT） | `MAIN-LOGIC` |
| `CSRCH-BY-ADDRESS` | [src/csrch-by-address.cob](../../../subsystems/04-customersearch/src/csrch-by-address.cob) | 住所部分一致（全件走査 INSPECT） | `MAIN-LOGIC` |
| `CSRCH-LIST-PAGED` | [src/csrch-list-paged.cob](../../../subsystems/04-customersearch/src/csrch-list-paged.cob) | ページング一覧 | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） |
| 実行契機 | API要求ごと（A/D/P/継続） |
| 多重度・冪等性 | 参照系は冪等・並行可。ページングはカーソル（`CSRCH-START-AFTER`）で継続 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[CSRCH-AND]（CSRCH-OP='A'）
1. カナ前方一致で最大200件抽出
2. 電話前方一致で最大200件抽出
3. 両集合の積集合（INTERSECT）を結果として返す

[CSRCH-BY-ADDRESS]（'D'）
1. 全件走査し、住所に部分文字列を INSPECT で照合

[CSRCH-LIST-PAGED]（'P'）
1. CSRCH-START-AFTER をカーソルに START → READ NEXT を CSRCH-PAGE-SIZE 件
2. 末尾IDを CSRCH-LAST-ID に設定（次ページのカーソル）
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 操作区分 `CSRCH-OP` | `A`=AND検索 / `D`=住所検索 / `P`=ページング / ` `=継続 |
| 2 | AND検索の上限 | 各条件最大200件を抽出し積集合を取る |
| 3 | ページング | `CSRCH-START-AFTER`/`CSRCH-LAST-ID` でカーソル継続 |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 1件以上一致 |
| `10` | EOF | 反復取得の終端 |
| `16` | 致命的エラー | I/O失敗等 |

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引I/O失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/csrch-api.cpy](../../../subsystems/04-customersearch/copy/api/csrch-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `CSRCH-KANA-PREFIX` | `X(50)` | △ | カナ前方一致 | `A` 時 |
| `CSRCH-PHONE-PREFIX` | `X(15)` | △ | 電話前方一致 | `A` 時 |
| `CSRCH-ADDR-SUBSTR` | `X(50)` | △ | 住所部分一致 | `D` 時 |
| `CSRCH-PAGE-SIZE` | `9(3)` | △ | ページサイズ | `P` 時 |
| `CSRCH-START-AFTER` | `9(10)` | △ | カーソル（このID以降） | `P` 継続時 |
| `CSRCH-OP` | `X(1)` | ✓ | 操作区分 | `A`/`D`/`P`/` ` |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `customer.idx` | 固定長 | `CR-ID` | 03-customer の索引を共用 |
| テーブル | `customers` | PostgreSQL | 顧客ID(10) | TO-BE |

### 3.3 前提・事前条件

- 03-customer の `customer.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `CSRCH-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `CSRCH-MATCH-ID` | `9(10)` | 一致顧客ID | 一致時 |
| `CSRCH-MATCH-KANA` | `X(50)` | カナ | 一致時 |
| `CSRCH-MATCH-KANJI` | `X(60)` | 漢字 | 一致時 |
| `CSRCH-MATCH-PHONE` | `X(15)` | 電話 | 一致時 |
| `CSRCH-MATCH-ADDR` | `X(200)` | 住所 | 一致時 |
| `CSRCH-LAST-ID` | `9(10)` | 次ページカーソル | ページング時 |

### 4.2 出力データ更新（更新系の場合）

更新なし（参照専用）。

### 4.3 後続・事後条件

- ページング時、`CSRCH-LAST-ID` を次回呼び出しの `CSRCH-START-AFTER` に渡す。

---

## 5. レコード定義

レコードレイアウトは 03-customer の [copy/private/fd-customer.cpy](../../../subsystems/03-customer/copy/private/fd-customer.cpy) を参照（本サブシステムは自前データなし）。

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `CR-ID` | `9(10)` | 主キー | 顧客ID |
| `CR-KANA` | `X(50)` | 副キー | カナ氏名 |
| `CR-PHONE` | `X(15)` | 副キー | 電話番号 |
| `CR-ADDRESS` | `X(200)` | — | 住所 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | AND検索 | 各200件抽出→INTERSECT | SQL `WHERE kana LIKE ? AND phone LIKE ?` | 一括クエリ化 |
| 2 | 住所部分一致 | 全件走査 INSPECT | SQL `LIKE '%...%'`（性能注意） | インデックス戦略を検討 |
| 3 | ページング | カーソルID | キーセットページネーション | `CSRCH-LAST-ID` 相当を維持 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | 住所全件走査の性能 | TO-BE でのインデックス／全文検索の要否を検討 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
