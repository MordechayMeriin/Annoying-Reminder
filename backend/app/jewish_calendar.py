"""
Shabbat and holidays as don't-disturb time (pure, no I/O). Uses the hdate library.

A "rest period" runs from candle lighting before the first day of Shabbat / yom tov until havdalah
after the last one, so a holiday next to Shabbat (e.g. Rosh Hashana on Friday-Saturday) is one
period. It is widened by a buffer on each side (prepare before, settle down after).
Only Shabbat and yom tov count: not chol hamoed, fast days, Chanukah or Purim.
"""

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from functools import lru_cache
from typing import Any
from zoneinfo import ZoneInfo

from hdate import HDateInfo, Location, Zmanim


@dataclass(frozen=True)
class RestConfig:
    tz: ZoneInfo
    latitude: float
    longitude: float
    altitude: float = 0
    diaspora: bool = False
    candle_lighting_min: int = 20   # candle lighting = sunset - this
    havdalah_min: int = 0           # 0 = nightfall (three stars); otherwise sunset + this
    before_min: int = 60            # quiet starts this long before candle lighting
    after_min: int = 60             # ... and ends this long after havdalah


def rest_config(s: Any) -> RestConfig | None:
    """From the app settings; None when Shabbat mode is off."""
    if not s.shabbat_mode:
        return None
    return RestConfig(tz=s.tz, latitude=s.latitude, longitude=s.longitude, altitude=s.altitude,
                      diaspora=s.diaspora, candle_lighting_min=s.candle_lighting_min,
                      havdalah_min=s.havdalah_min, before_min=s.shabbat_before_min,
                      after_min=s.shabbat_after_min)


@dataclass(frozen=True)
class RestPeriod:
    start: datetime   # including the buffer before
    end: datetime     # including the buffer after
    name: str         # "שבת", "ראש השנה", "שבת · ראש השנה" …


@lru_cache(maxsize=512)
def _day(d: date, diaspora: bool) -> tuple[bool, tuple[str, ...]]:
    """(is a rest day, names) for a civil date."""
    info = HDateInfo(d, diaspora=diaspora)
    names = (["שבת"] if info.is_shabbat else []) + [str(h) for h in info.holidays if info.is_yom_tov]
    return bool(info.is_shabbat or info.is_yom_tov), tuple(names)


def _zmanim(d: date, cfg: RestConfig) -> Zmanim:
    loc = Location(name="", latitude=cfg.latitude, longitude=cfg.longitude, timezone=cfg.tz,
                   altitude=cfg.altitude, diaspora=cfg.diaspora)
    return Zmanim(date=d, location=loc, candle_lighting_offset=cfg.candle_lighting_min,
                  havdalah_offset=cfg.havdalah_min)


@lru_cache(maxsize=256)
def _period_from(first: date, cfg: RestConfig) -> RestPeriod:
    """The rest period whose first rest day is `first`."""
    last, names = first, list(_day(first, cfg.diaspora)[1])
    while _day(last + timedelta(days=1), cfg.diaspora)[0]:
        last += timedelta(days=1)
        names += [n for n in _day(last, cfg.diaspora)[1] if n not in names]
    candles = _zmanim(first - timedelta(days=1), cfg).candle_lighting
    havdalah = _zmanim(last, cfg).havdalah
    # Fallbacks only matter for extreme latitudes where the sun doesn't set.
    if candles is None:
        candles = datetime(first.year, first.month, first.day, tzinfo=cfg.tz) - timedelta(hours=6)
    if havdalah is None:
        havdalah = datetime(last.year, last.month, last.day, 20, tzinfo=cfg.tz)
    names_sorted = (["שבת"] if "שבת" in names else []) + [n for n in names if n != "שבת"]
    return RestPeriod(start=candles - timedelta(minutes=cfg.before_min),
                      end=havdalah + timedelta(minutes=cfg.after_min),
                      name=" · ".join(names_sorted))


def periods_between(start: date, end: date, cfg: RestConfig) -> list[RestPeriod]:
    """Rest periods whose first rest day is in [start, end]."""
    out, d = [], start
    while d <= end:
        if _day(d, cfg.diaspora)[0] and not _day(d - timedelta(days=1), cfg.diaspora)[0]:
            out.append(_period_from(d, cfg))
        d += timedelta(days=1)
    return out


def current_period(now: datetime, cfg: RestConfig) -> RestPeriod | None:
    """The rest period `now` falls in (buffers included), or None."""
    today = now.astimezone(cfg.tz).date()
    # A period starts on the evening before its first day and lasts at most ~3 days.
    for p in periods_between(today - timedelta(days=4), today + timedelta(days=1), cfg):
        if p.start <= now < p.end:
            return p
    return None


def next_period(now: datetime, cfg: RestConfig) -> RestPeriod | None:
    """The current rest period, or the next one (within ~5 weeks; there's always a Shabbat)."""
    today = now.astimezone(cfg.tz).date()
    for p in periods_between(today - timedelta(days=4), today + timedelta(days=35), cfg):
        if now < p.end:
            return p
    return None


def rest_until(now: datetime, cfg: RestConfig | None) -> datetime | None:
    """When the current rest period ends (buffer included), or None if it isn't one now."""
    if cfg is None:
        return None
    p = current_period(now, cfg)
    return p.end if p else None
