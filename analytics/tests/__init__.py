"""Analytics test package configuration.

Ensures both the ``analytics`` package root (for ``meridian``) and the
``analytics/tests`` directory (for shared test helpers such as
``mrd244_helpers``) are importable regardless of the working directory
from which ``pytest`` or ``unittest`` is invoked.
"""
import os
import sys

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_ANALYTICS_DIR = os.path.dirname(_TESTS_DIR)

for _path in (_ANALYTICS_DIR, _TESTS_DIR):
    if _path not in sys.path:
        sys.path.insert(0, _path)
