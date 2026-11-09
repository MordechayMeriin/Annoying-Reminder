"""Behaviour settings: validation, overrides from the app, saving and describing them."""

from datetime import datetime, time
from zoneinfo import ZoneInfo

import pytest

from app import config, user_settings


@pytest.fixture(autouse=True)
def clean_overrides():
    config.apply_overrides({})
    yield
    config.apply_overrides({})


def test_defaults_without_env():
    s = config.get_settings()
    assert s.morning_time == time(7, 30) and s.checkin_times == [time(13), time(17)]
    assert s.shabbat_mode and (s.latitude, s.longitude) == (32.0956, 34.9566)
    assert (s.candle_lighting_min, s.havdalah_min, s.shabbat_before_min, s.shabbat_after_min) == (20, 0, 60, 60)


@pytest.mark.parametrize("key, raw, canonical", [
    ("morning_time", "8:5", "08:05"),
    ("checkin_times", "17:00,13:00", "13:00, 17:00"),
    ("checkin_times", "", ""),
    ("meetings_eve_time", "OFF", "off"),
    ("week_start_day", "Sunday", "sun"),
    ("shabbat_mode", "no", "false"),
    ("timezone", "Europe/London", "Europe/London"),
    ("latitude", "31.5", "31.5"),
])
def test_canonical_values(key, raw, canonical):
    assert config.validate({key: raw}) == ({key: canonical}, {})


@pytest.mark.parametrize("key, raw", [
    ("morning_time", "25:00"), ("morning_time", "soon"), ("max_nags", "0"), ("max_nags", "2.5"),
    ("latitude", "100"), ("timezone", "Mars/Base"), ("week_start_day", "someday"), ("nope", "1"),
])
def test_invalid_values(key, raw):
    ok, errors = config.validate({key: raw})
    assert ok == {} and key in errors


def test_overrides_change_get_settings():
    config.apply_overrides({"morning_time": "06:45", "timezone": "Europe/London"})
    s = config.get_settings()
    assert s.morning_time == time(6, 45) and s.tz == ZoneInfo("Europe/London")
    assert s.database_url == "postgresql://test/test"  # env-only values untouched


class FakeDB:
    """Just enough of db.* for user_settings."""

    def __init__(self):
        self.rows: dict[str, str] = {}

    def fetch_all(self, sql, params=()):
        return [{"key": k, "value": v} for k, v in self.rows.items()]

    def connect(self):
        db = self

        class Conn:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def execute(self, sql, params):
                if sql.lstrip().startswith("insert"):
                    db.rows[params[0]] = params[1]
                else:
                    for k in params[0]:
                        db.rows.pop(k, None)

        return Conn()


@pytest.fixture
def fake_db(monkeypatch):
    fake = FakeDB()
    monkeypatch.setattr(user_settings.db, "fetch_all", fake.fetch_all)
    monkeypatch.setattr(user_settings.db, "connect", fake.connect)
    return fake


def test_save_reset_and_reload(fake_db):
    assert user_settings.save({"evening_time": "22:15", "shabbat_after_min": "45"}) == {}
    assert fake_db.rows == {"evening_time": "22:15", "shabbat_after_min": "45"}
    assert config.get_settings().evening_time == time(22, 15)

    assert user_settings.save({"evening_time": None}) == {}  # back to default
    assert config.get_settings().evening_time == time(21, 30)
    assert fake_db.rows == {"shabbat_after_min": "45"}


def test_save_rejects_everything_if_one_is_invalid(fake_db):
    errors = user_settings.save({"evening_time": "22:15", "max_nags": "-1"})
    assert set(errors) == {"max_nags"} and fake_db.rows == {}


def test_bad_stored_value_is_ignored(fake_db):
    fake_db.rows = {"morning_time": "garbage", "evening_time": "20:00", "removed_setting": "1"}
    user_settings.load()
    s = config.get_settings()
    assert s.morning_time == time(7, 30) and s.evening_time == time(20)


def test_describe(fake_db):
    user_settings.save({"max_nags": "6"})
    d = user_settings.describe(datetime(2026, 11, 9, 12, tzinfo=ZoneInfo("Asia/Jerusalem")))
    by_key = {f["key"]: f for f in d["fields"]}
    assert by_key["max_nags"]["value"] == "6" and by_key["max_nags"]["overridden"]
    assert by_key["max_nags"]["default"] == "4"
    assert not by_key["morning_time"]["overridden"]
    assert d["rest"] == {"name": "שבת", "start": "2026-11-13T15:23+02:00", "end": "2026-11-14T18:20+02:00",
                         "active": False}
