# Runtime environment sample (redacted)

> **Security note**: This file is intentionally **redacted**. Do **not** commit live secrets.
> Replace sensitive values with placeholders before sharing or versioning.

## Summary

- ES monitor is **enabled** when `CP_ES_URL` is non-empty.
- Exensio API monitor is **enabled** when `EXENSIO_ENABLED=true` and `EXENSIO_*_URL` is set.

Based on the provided runtime dump, **both ES and Exensio are enabled** (values redacted below).

---

## Environment (redacted)

```
EXENSIO_ENV=PROD
EXENSIO_ENABLED=true
EXENSIO_PROD_URL=<redacted>
EXENSIO_USERNAME=<redacted>
EXENSIO_PASSWORD=<redacted>
EXENSIO_DBSCHEMA=PRODUCTION

CP_ES_URL=<redacted>
CP_ES_USERNAME=<redacted>
CP_ES_PASSWORD=<redacted>
CP_ES_API_KEY=<redacted>

ETL_TRIGGER_ENABLED=true
ONSEMI_SSO_ENABLED=false

REFDB_TNS=<redacted>
REFDB_USER=<redacted>
REFDB_PASS=<redacted>

JAVA_HOME=/apps/exensio/jdk-21
CATALINA_HOME=/apps/exensio/tomcat/tomcat-10
MAVEN_HOME=/apps/exensio/maven
TNS_ADMIN=/export/home/dpower/tns
PATH=<redacted>
```

---

## Guidance

- Keep secrets in your deployment vault or environment manager.
- If you need the full dump in ops history, store it in a secured location **outside** the repo.
