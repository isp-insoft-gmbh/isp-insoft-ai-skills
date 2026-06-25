# Duplicate review flow

Example sequence from the original duplicate-review app spike. Treat it as a
recipe, not a script.

```sh
./mvnw -q package
java -jar @fxdriver.skill.jar@ attach <pid>
export FXDRIVER_TOKEN=<printed-token>
java -jar @fxdriver.skill.jar@ rpc <printed-port> ping '{}'
java -jar @fxdriver.skill.jar@ screenshot \
  <printed-port> target/dup-before.png > target/dup-before-summary.json
java -jar @fxdriver.skill.jar@ rpc <printed-port> click '{"nodeId":"find-duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> wait '{"text":"portrait","timeoutMs":30000}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> setText '{"nodeId":"filter-duplicates","value":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> selectListItem '{"nodeId":"duplicates-list","itemText":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> assert '{"textExact":"Keep duplicates","present":true}'
java -jar @fxdriver.skill.jar@ screenshot \
  <printed-port> target/dup-detail.png > target/dup-detail-summary.json
java -jar @fxdriver.skill.jar@ rpc <printed-port> click '{"textExact":"Keep duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> assert '{"text":"Kept duplicates","present":true}'
java -jar @fxdriver.skill.jar@ screenshot \
  <printed-port> target/dup-after.png > target/dup-after-summary.json
java -jar @fxdriver.skill.jar@ image-diff \
  target/dup-before.png target/dup-after.png target/dup-diff.png \
  > target/dup-diff.json
java -jar @fxdriver.skill.jar@ rpc <printed-port> events '{}' > target/dup-events.json
```
