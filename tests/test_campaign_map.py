import json
from pathlib import Path


def test_campaign_map_schema():
    path = Path(__file__).parents[1] / "src/main/resources/data/muxi_outbreak/outbreak_maps/campaign_city_zero.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    assert data["mode"] == "campaign"
    assert data["start"]
    assert data["finish"]
    assert len(data["safeRooms"]) >= 1
    assert len(data["hordeSpawns"]) >= 1
