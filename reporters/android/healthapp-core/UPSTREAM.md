# HealthAPP band reader

The protocol sources in this module are copied from [zyp556678/HealthAPP at 42102af](https://github.com/zyp556678/HealthAPP/tree/42102afcd9c7aec2f1df90997440b792ec0bab88/core/data/src/main/kotlin/com/example/healthapp/data) under AGPL-3.0. The packaged license is in `src/main/assets/healthapp/LICENSE-AGPL-3.0.txt`. Hibi integrates its own Android transport, credential storage, scheduling and queue in `:hibi-band`.

Hibi collects every ID block in authenticated activity-index replies. Multiple fitness message fragments within one envelope are merged before parsing. These compatibility changes are local to Hibi.
