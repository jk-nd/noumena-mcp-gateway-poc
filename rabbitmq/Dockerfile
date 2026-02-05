FROM rabbitmq:3-management-alpine

USER root
RUN apk update && apk add --no-cache gettext bash

COPY ./scripts/entrypoint.sh /rabbitmq-entrypoint.sh
COPY ./etc/definitions.json.template /etc/rabbitmq/definitions.json.template
COPY ./etc/rabbitmq.template /etc/rabbitmq/rabbitmq.template
COPY ./etc/enabled_plugins /etc/rabbitmq/enabled_plugins

RUN chmod +x /rabbitmq-entrypoint.sh

USER rabbitmq

ENTRYPOINT ["/rabbitmq-entrypoint.sh"]
