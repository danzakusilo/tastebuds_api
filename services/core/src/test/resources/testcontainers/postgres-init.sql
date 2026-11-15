-- Test-only roles mirroring production (docs/PLAN.md §8.4). Throwaway container credentials, not secrets.
CREATE ROLE app_rw NOLOGIN;
CREATE ROLE tastebuds_app LOGIN PASSWORD 'test' IN ROLE app_rw;
