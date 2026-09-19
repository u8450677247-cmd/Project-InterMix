"""Project Intermix distributed continuity service.

The package deliberately uses only the Python standard library.  It is the
ordinary-service implementation used to prove the LIBRARIAN-01 protocol before
any Android ROM or native-daemon integration.
"""

from .models import EventEnvelope, IngestResult, ValidationError
from .store import ContinuityStore

__all__ = ["ContinuityStore", "EventEnvelope", "IngestResult", "ValidationError"]
