FROM hashicorp/terraform:1.10

# Install curl and jq for API calls and JSON parsing
RUN apk add --no-cache curl jq

WORKDIR /terraform
COPY providers.tf terraform.tf /terraform/
COPY *.sh /
RUN chmod +x /*.sh
VOLUME /state
ENV TF_VAR_app_name nplintegrations

RUN terraform init
ENTRYPOINT []
CMD ["local.sh"]
