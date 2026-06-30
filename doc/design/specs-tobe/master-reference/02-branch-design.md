# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `02-branch` |
| ディレクトリ | [subsystems/02-branch/](../../../subsystems/02-branch/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/br-api.cpy](../../../subsystems/02-branch/copy/api/br-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

支店コードをキーに支店マスタを参照し、支店名（漢字・カナ）・地域・状態を返す。地域別一覧および全件一覧も提供する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 取引パイプライン / 口座系 | 支店情報の参照 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `branch.idx`（ISAM） / `branches`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `BR-LOAD` | [src/br-load.cob](../../../subsystems/02-branch/src/br-load.cob) | シードを索引へロード | `MAIN-LOGIC` ほか |
| `BR-LOOKUP` | [src/br-lookup.cob](../../../subsystems/02-branch/src/br-lookup.cob) | 支店コードで参照（ファイル永続オープン） | `MAIN-LOGIC` |
| `BR-LIST-ALL` | [src/br-list-all.cob](../../../subsystems/02-branch/src/br-list-all.cob) | 全件順次（START LOW-VALUES→READ NEXT） | `MAIN-LOGIC` |
| `BR-LIST-BY-REGION` | [src/br-list-by-region.cob](../../../subsystems/02-branch/src/br-list-by-region.cob) | 地域別（副キー `BR-REC-REGION`） | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと（L/R/A） ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可。LOOKUP はファイル永続オープン |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[BR-LOOKUP]（BR-IN-OP='L'）
1. BR-IN-CODE を主キーに索引を READ
2. 該当なし→04
3. 出力エリアへ支店名・地域・状態をセット

[BR-LIST-ALL]（'A'）
1. START LOW-VALUES → READ NEXT を反復
2. EOF で 10 を返す

[BR-LIST-BY-REGION]（'R'）
1. 副キー BR-REC-REGION で START → READ NEXT
2. 地域不一致 or EOF で終了
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 操作区分 `BR-IN-OP` | `L`=参照 / `R`=地域一覧 / `A`=全件 |
| 2 | 一覧系の終端 | EOF時は `10` を返す |
| 3 | `08` は未使用 | API定義はあるがプログラムは返さない |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照・取得に成功 |
| `04` | 該当なし | 主キー一致レコードなし |
| `10` | EOF | 一覧反復取得の終端 |
| `16` | 致命的エラー | 索引オープン失敗等 |

> 注: `08`（`BR-STATUS-INVALID`）はAPIに定義されるが、いずれのプログラムも返さない。

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。LOOKUP はファイルを永続オープンして再利用。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引オープン失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/br-api.cpy](../../../subsystems/02-branch/copy/api/br-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `BR-IN-CODE` | `X(3)` | △ | 支店コード | `L` 時必須 |
| `BR-IN-REGION` | `X(20)` | △ | 地域名 | `R` 時必須 |
| `BR-IN-OP` | `X(1)` | ✓ | 操作区分 | `L`=参照 / `R`=地域一覧 / `A`=全件 |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `branch.idx` | 固定長132バイト | `BR-REC-CODE`(3) | 副キー `BR-REC-REGION` |
| 入力ファイル | [data/](../../../subsystems/02-branch/data/) | 行順次 | — | LOAD 対象 |
| テーブル | `branches` | PostgreSQL | 支店コード(3) | TO-BE |

### 3.3 前提・事前条件

- `branch.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `BR-OUT-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `BR-OUT-CODE` | `X(3)` | 支店コード | 正常時 |
| `BR-OUT-NAME-KANJI` | `X(40)` | 支店名（漢字） | 正常時 |
| `BR-OUT-NAME-KANA` | `X(40)` | 支店名（カナ） | 正常時 |
| `BR-OUT-REGION` | `X(20)` | 地域 | 正常時 |
| `BR-OUT-STATUS-CODE` | `X(1)` | 支店状態 | 正常時 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `branch.idx` | WRITE | 全レコード | LOAD 時のみ |

### 4.3 後続・事後条件

- 参照結果が取引・口座処理で支店情報として利用される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-branch.cpy](../../../subsystems/02-branch/copy/private/fd-branch.cpy)（レコード長132バイト固定）

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `BR-REC-CODE` | `X(3)` | 主キー | 支店コード |
| `BR-REC-NAME-KANJI` | `X(40)` | — | 支店名（漢字） |
| `BR-REC-NAME-KANA` | `X(40)` | — | 支店名（カナ） |
| `BR-REC-REGION` | `X(20)` | 副キー | 地域 |
| `BR-REC-OPENED-DATE` | `9(8)` | — | 開設日 |
| `BR-REC-STATUS` | `X(1)` | — | 状態 |
| `BR-REC-FILLER` | `X(20)` | — | 予約・未使用 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | `BR-REC-FILLER` | 20バイト予約 | 列なし | スコープ外 |
| 2 | API | CALL（br-api.cpy） | REST（一覧はクエリ） | Master Reference App 側で実装 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | `08` 入力不正の扱い | TO-BE では入力検証を明示実装するか検討 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
