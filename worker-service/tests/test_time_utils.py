from app.common.time_utils import format_duration, format_timestamp


def test_format_duration_minutes_and_seconds():
    assert format_duration(842) == "14:02"


def test_format_duration_hours_minutes_and_seconds():
    assert format_duration(3723) == "01:02:03"


def test_format_timestamp_matches_duration_style():
    assert format_timestamp(12.8) == "00:12"
