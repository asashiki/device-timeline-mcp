"""Deployment settings shared by the optional Hibi Python helpers."""
import ipaddress
import os
from datetime import datetime, timezone
from urllib.parse import urlsplit
from uuid import UUID

HEALTH_DEVICE = os.getenv('HIBI_HEALTH_DEVICE_ID', 'android-phone')
MAC_DEVICE = os.getenv('HIBI_MAC_DEVICE_ID', 'mac-laptop')


def notion_id(name):
    value = os.environ.get(name)
    if not value:
        raise ValueError(f'Set {name} to the UUID in your Notion workspace')
    return str(UUID(value))


def origin(name, *, internal=False):
    default = 'http://127.0.0.1:4823' if internal else ''
    value = os.getenv(name, default).rstrip('/')
    url = urlsplit(value)
    if not url.hostname or url.username or url.password or url.path or url.query or url.fragment:
        raise ValueError(f'Set {name} to a host origin')
    if url.scheme != 'https':
        if url.scheme != 'http':
            raise ValueError(f'Invalid scheme for {name}')
        try:
            address = ipaddress.ip_address(url.hostname)
            local = address.is_loopback or (internal and address.is_private and not address.is_unspecified)
        except ValueError:
            local = url.hostname == 'localhost'
        if not local:
            raise ValueError(f'Use HTTPS for {name}')
    return value


def legacy_idle_cutoff():
    text = os.getenv('HIBI_MAC_LEGACY_IDLE_BEFORE')
    if not text:
        return None
    value = datetime.fromisoformat(text.replace('Z','+00:00'))
    if value.tzinfo is None:
        raise ValueError('HIBI_MAC_LEGACY_IDLE_BEFORE requires a timezone')
    return value.astimezone(timezone.utc)
