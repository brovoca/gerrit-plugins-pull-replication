# What is this for?

This docker compose sets up primary and replica nodes using pull-replication to
replicate with notifications over a broker. In this case our broker
implementation is Kafka.

Copy the pull-replication, events-nats, and events-broker artifacts to test
into this directory:

```bash
cp $GERRIT_HOME/bazel-bin/plugins/pull-replication/pull-replication.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-nats/events-nats.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-broker/events-broker.jar .
```

Start up the application using docker compose:

```bash
export NATS_URL=nats.localdomain:4222
export NATS_USER="nats_client"
export NATS_PASSWORD="1234pass"
docker-compose -p nats up
```
