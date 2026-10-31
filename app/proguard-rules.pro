# OkHttp, coroutines and AndroidX ship their own consumer rules; nothing here uses reflection,
# so no extra keep rules are needed. Add them only if a release build crashes where debug doesn't.
