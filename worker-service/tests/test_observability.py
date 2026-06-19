from app.observability import record_stage_duration
from app.observability import record_task_failed
from app.observability import record_task_processed


def test_observability_helpers_are_safe_to_call():
    record_task_processed("COMPLETED")
    record_task_failed()
    record_stage_duration("download", 1.25)
    record_stage_duration("unknown", 0.5)
