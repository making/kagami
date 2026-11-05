# 006 整数と期限の入力検証

Difficulty: Low

## 背景

VulnHunter 監査 (2026-11-05、`kagami_VULNHUNT_RESULTS_2026-11-05-184031`) の Low 指摘のうち、
「整数と期限」の観点をまとめて処理する。他の Low 指摘は保留中。

## 対象

- VULN-003: `POST /token` の `expires_in` が未検証。オーバーフローで 500、負値で期限切れトークンを発行
  (`token/web/TokenController.java:35` -> `token/TokenIssuer.java:41`)
- VULN-004: `POST /app/token` の `duration * 24 * 30` がオーバーフロー
  (`token/web/TokenPageController.java:118-122`)
- VULN-009: `olderThanHours` が Instant のオーバーフロー域に入ると例外で 500
  (`repository/web/RepositoryAdminController.java:154-164`)

## 方針

- 有効期限の計算を 1 か所に集約し、範囲外の値は 400/422 で明示的に拒否する (オーバーフローを計算前に検出)
- 期限の下限・上限を設定値として持たせるかは実装時に判断する
- 各所に個別のガードを散らさず、`Duration` ベースの共通ヘルパーに寄せる

## 完了条件

- 3 経路すべてで、範囲外の入力が 5xx ではなく 4xx になる
- 既存のテストを拡張して境界値 (最大値、負値、オーバーフロー直前) を覆う
- 監査レポートの該当 finding を解消済みとして記録する
