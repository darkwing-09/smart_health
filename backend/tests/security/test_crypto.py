"""
Unit tests for Envelope Encryption, Key Versioning, and Key Rotation.
"""

import pytest
from app.core.crypto import (
    EnvelopeEncryptionService,
    KeyManagementError,
    DecryptionError
)


def test_envelope_encryption_roundtrip():
    """Verifies that sensitive clinical text encrypts and decrypts with 100% fidelity."""
    kid, key_b64 = EnvelopeEncryptionService.generate_new_key_b64()
    service = EnvelopeEncryptionService(current_key_id=kid, current_key_b64=key_b64)

    sensitive_physician_note = (
        "Patient exhibits nocturnal resting tachycardia (peak 152 bpm at 03:14 UTC). "
        "No chest discomfort reported. Advised Holter monitor follow-up."
    )

    encrypted = service.encrypt(sensitive_physician_note)
    assert encrypted.startswith(f"env:{kid}:")
    assert sensitive_physician_note not in encrypted

    decrypted = service.decrypt(encrypted)
    assert decrypted == sensitive_physician_note


def test_key_rotation_and_historical_decryption():
    """
    Simulates production key rotation:
    1. Records encrypted under v1.
    2. Master key rotated to v2, with v1 preserved in old_keys.
    3. Legacy v1 records decrypt transparently.
    4. New records are encrypted under v2.
    """
    v1_id, v1_key = EnvelopeEncryptionService.generate_new_key_b64()
    v2_id, v2_key = EnvelopeEncryptionService.generate_new_key_b64()

    # Phase A: Service running with v1
    service_v1 = EnvelopeEncryptionService(current_key_id=v1_id, current_key_b64=v1_key)
    note_a = "Historic clinical note from 2026-08-01."
    token_v1 = service_v1.encrypt(note_a)
    assert token_v1.startswith(f"env:{v1_id}:")

    # Phase B: Key rotated to v2, v1 demoted to old_keys
    service_v2 = EnvelopeEncryptionService(
        current_key_id=v2_id,
        current_key_b64=v2_key,
        old_keys_map={v1_id: v1_key}
    )

    # Historical v1 token still decrypts seamlessly
    decrypted_v1 = service_v2.decrypt(token_v1)
    assert decrypted_v1 == note_a

    # New records encrypt under v2
    note_b = "Current clinical note from 2026-09-04."
    token_v2 = service_v2.encrypt(note_b)
    assert token_v2.startswith(f"env:{v2_id}:")
    assert service_v2.decrypt(token_v2) == note_b


def test_reencryption_migration():
    """Verifies that historical records can be migrated to the latest key version."""
    v1_id, v1_key = EnvelopeEncryptionService.generate_new_key_b64()
    v2_id, v2_key = EnvelopeEncryptionService.generate_new_key_b64()

    service = EnvelopeEncryptionService(
        current_key_id=v2_id,
        current_key_b64=v2_key,
        old_keys_map={v1_id: v1_key}
    )

    original_text = "Highly confidential patient diagnostic discussion."
    # Simulate legacy token
    legacy_service = EnvelopeEncryptionService(current_key_id=v1_id, current_key_b64=v1_key)
    legacy_token = legacy_service.encrypt(original_text)
    assert legacy_token.startswith(f"env:{v1_id}:")

    # Migrate to current key (v2)
    migrated_token = service.reencrypt(legacy_token)
    assert migrated_token.startswith(f"env:{v2_id}:")

    # Verify migrated token decrypts cleanly
    assert service.decrypt(migrated_token) == original_text


def test_tampered_ciphertext_detection():
    """Verifies that tampering with ciphertext or authentication tag raises DecryptionError."""
    kid, key_b64 = EnvelopeEncryptionService.generate_new_key_b64()
    service = EnvelopeEncryptionService(current_key_id=kid, current_key_b64=key_b64)

    token = service.encrypt("Legitimate medical findings.")
    parts = token.split(":")
    # Tamper with the last byte of the encrypted payload
    tampered_payload = parts[5][:-2] + ("AA" if parts[5][-2:] != "AA" else "BB")
    tampered_token = f"{parts[0]}:{parts[1]}:{parts[2]}:{parts[3]}:{parts[4]}:{tampered_payload}"

    with pytest.raises(DecryptionError):
        service.decrypt(tampered_token)


def test_missing_key_handling():
    """Verifies that attempting to decrypt with an unconfigured key version raises KeyManagementError."""
    kid, key_b64 = EnvelopeEncryptionService.generate_new_key_b64()
    service = EnvelopeEncryptionService(current_key_id=kid, current_key_b64=key_b64)

    # Token claiming key version 'v999' which does not exist in key ring
    bogus_token = "env:v999:AAAA:BBBB:CCCC:DDDD"
    with pytest.raises(KeyManagementError):
        service.decrypt(bogus_token)
