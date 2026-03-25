#!/usr/bin/env python3
"""Fetches the next 2 weeks of events from the 35services calendar, including recurring events."""
import urllib.request
from datetime import datetime, timedelta
import zoneinfo
import recurring_ical_events
from icalendar import Calendar

ICAL_URL = "https://calendar.google.com/calendar/ical/5usct2veeabp9unuefaojgif88%40group.calendar.google.com/public/basic.ics"

def is_internal(summary, desc=""):
    text = (summary + " " + desc).lower()
    return "intern" in text

def fetch_upcoming(days=14):
    berlin = zoneinfo.ZoneInfo("Europe/Berlin")
    now = datetime.now(berlin)
    cutoff = now + timedelta(days=days)

    with urllib.request.urlopen(ICAL_URL, timeout=10) as r:
        cal = Calendar.from_ical(r.read())

    events = recurring_ical_events.of(cal).between(now, cutoff)

    result = []
    for e in events:
        dt = e["DTSTART"].dt
        if not hasattr(dt, "hour"):
            dt = datetime(dt.year, dt.month, dt.day, tzinfo=berlin)
        dt = dt.astimezone(berlin)

        end = e["DTEND"].dt
        if hasattr(end, "hour"):
            end = end.astimezone(berlin)
        else:
            end = datetime(end.year, end.month, end.day, tzinfo=berlin)

        summary = str(e.get("SUMMARY", "?"))
        desc = str(e.get("DESCRIPTION", ""))
        internal = is_internal(summary, desc)
        result.append((dt, end, summary, desc, internal))

    result.sort(key=lambda x: x[0])
    return result, now

if __name__ == "__main__":
    upcoming, now = fetch_upcoming()
    print(f"Öffnungszeiten & Termine (nächste 2 Wochen ab {now.strftime('%d.%m.%Y')}):\n")
    if not upcoming:
        print("Keine Termine gefunden.")
    for start, end, summary, desc, internal in upcoming:
        tag = " ⚠️ [INTERN]" if internal else ""
        print(f"📅 {start.strftime('%A, %d.%m.%Y')} {start.strftime('%H:%M')}–{end.strftime('%H:%M')} Uhr{tag}")
        print(f"   {summary}")
        if desc:
            short = desc.replace("\\n", " ").strip()[:150]
            print(f"   ℹ️  {short}")
        print()
