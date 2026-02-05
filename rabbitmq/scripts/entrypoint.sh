#!/bin/bash
envsubst < /etc/rabbitmq/definitions.json.template > /etc/rabbitmq/definitions.json
envsubst < /etc/rabbitmq/rabbitmq.template > /etc/rabbitmq/rabbitmq.conf

if [ $# -eq 0 ]; then
    set -- rabbitmq-server
fi

./usr/local/bin/docker-entrypoint.sh "$@"
