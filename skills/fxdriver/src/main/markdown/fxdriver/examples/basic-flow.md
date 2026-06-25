# Basic flow

Replace `<pid>` with the target JavaFX JVM PID. Use the printed port/token from
`attach`.

```sh
./mvnw -q package
java -jar @fxdriver.skill.jar@ attach <pid>
export FXDRIVER_TOKEN=<printed-token>
java -jar @fxdriver.skill.jar@ rpc <printed-port> ping '{}'
java -jar @fxdriver.skill.jar@ rpc <printed-port> snapshot '{}' > target/basic-flow-snapshot.json
java -jar @fxdriver.skill.jar@ rpc <printed-port> highlight '{"selector":"type=Button"}'
java -jar @fxdriver.skill.jar@ screenshot \
  <printed-port> target/basic-flow.png > target/basic-flow-summary.json
java -jar @fxdriver.skill.jar@ rpc <printed-port> events '{}' > target/basic-flow-events.json
```
