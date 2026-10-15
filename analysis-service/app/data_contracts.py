"""Storage contracts shared with the Java application through one packaged JSON file."""
from copy import deepcopy
from functools import lru_cache
import hashlib
import json
from pathlib import Path


@lru_cache(maxsize=1)
def _contracts():
    path = Path(__file__).resolve().parents[1] / "config/data-contracts-v1.json"
    return json.loads(path.read_text(encoding="utf-8"))["contracts"]


def contract_for(dataset, product_type):
    matches = [value for value in _contracts()
               if dataset in value["datasets"] and product_type in value["productTypes"]]
    if len(matches) != 1:
        raise ValueError(f"Unsupported data contract: {dataset}/{product_type}")
    return deepcopy(matches[0])


@lru_cache(maxsize=64)
def contract_version(dataset, product_type):
    definition = contract_for(dataset, product_type)
    if dataset == "PRICE":
        from .providers import STRATEGY
        normalization = {key: STRATEGY.value("providers." + key) for key in ("history_adapter_version", "history_units", "tencent_volume_lot_size", "tencent_amount_unit")}
    else:
        from .research_data import config
        research = config()
        normalization = {key: research[key] for key in ("version", "fieldAliases", "fieldUnits", "dateColumns", "publicationColumns", "identityColumns")}
    canonical = json.dumps({"contract": definition, "normalization": normalization}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_quotes(records, product_type):
    contract = contract_for("PRICE", product_type)
    for record in records:
        for field in contract["requiredFields"]:
            if record.get(field) is None or record.get(field) == "":
                raise ValueError(f"Required source field is missing: {field}")
    return contract_version("PRICE", product_type)
