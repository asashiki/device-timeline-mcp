"""Create a per-user Hibi configuration before first installation."""
import getpass
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

path = Path.home() / ".config/hibi/mac.json"
if path.exists():
    sys.exit(f"Existing configuration: {path}")
url = input("Collector URL (https://hibi.example.org): ").strip().rstrip("/")
parts = urlsplit(url)
if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
    sys.exit("Enter a collector HTTPS URL without credentials, a query, or a fragment.")
token = getpass.getpass("Mac device token: ").strip()
if not token or any(character.isspace() for character in token):
    sys.exit("A Mac device token is required.")
os.umask(0o077)
path.parent.mkdir(parents=True, exist_ok=True)
with path.open("x", encoding="utf-8") as stream:
    json.dump({"serverUrl": url, "token": token}, stream)
    stream.write("\n")
print(f"Saved the configuration in {path}.")
