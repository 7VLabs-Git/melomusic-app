#!/usr/bin/env python3
import requests
import json

resp = requests.post("http://localhost:8000/api/auth/register", json={
    "email": "test123@example.com",
    "password": "pass123",
    "display_name": "Test User"
})

print(f"Status: {resp.status_code}")
print(f"Response: {resp.text}")
