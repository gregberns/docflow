# Architecture

```
                          +---------------------+
                          |    Anthropic API    |
                          |     (external)      |
                          +----------^----------+
                                     | HTTPS
                                     |
+---------------+   REST + SSE   +---+-----------+   JDBC    +---------------+
|  React SPA    +--------------->|  Spring Boot  +---------->| PostgreSQL 16 |
|  (frontend/)  |                |   backend     |           +---------------+
+---------------+                |  (backend/)   |
                                 +-------+-------+
                                         | file IO
                                         v
                                 +---------------+
                                 |  FS volume    |
                                 |  (PDF bytes)  |
                                 +---------------+
```

All three boxes (`frontend`, `backend`, `postgres`) run as Docker services on a shared bridge network; the FS volume is a Docker volume mounted into the backend at `STORAGE_ROOT`. Frontend talks to the backend via in-network DNS (`backend:8080`) in container, or Vite proxy in host-side dev. The backend is the only component that calls the Anthropic API or touches the FS volume.
