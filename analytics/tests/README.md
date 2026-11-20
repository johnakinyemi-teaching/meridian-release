# Archive MRD-143 parser tests

Run from the repository root inside the devcontainer:

```sh
python3 -m venv /tmp/mrd143-venv
/tmp/mrd143-venv/bin/python -m pip install pytest
/tmp/mrd143-venv/bin/python -m pytest analytics/tests -q
```

If venv support is missing in the course container, install it with
`sudo apt-get update && sudo apt-get install -y python3-venv`.

This snapshot contains 38 existing characterization cases and 8 additional
contract cases (46 total). Counts may grow when other team work is merged.
The existing fixtures and characterization tests are preserved unchanged.

- `test_mrd143_parser_characterization.py`: recorded outputs for six stations,
  every GUELPH daily value, column spans, overflow and file-level behaviour.
- `test_mrd143_parser_contract.py`: numeric partial last fields, malformed
  records between valid days, duplicate/out-of-order days and YIELD edge cases.
- `fixtures/`: existing outputs and baseline JSON/CSV. Do not regenerate these
  to make a changed parser pass; investigate any difference against the oracle.

The dedicated `.github/workflows/archive-parser-contract.yml` runs both
files. These fixture-based tests do not run the live model, ingest or nightly
pipeline. The default workflow retains its full build and live oracle check.
Run those checks in the devcontainer as described in the repository README.

The earlier README described MRD-246 and pipeline test files that are not
present in this uploaded snapshot. Do not claim those tests ran here.
Known parser limitations are documented in `docs/CONTRACTS.md`; MRD-143
records behaviour without changing the production parser.
