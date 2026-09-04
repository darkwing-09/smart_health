"""
Production Envelope Encryption & Key Rotation Engine.

Architecture:
- Master Key (Key Encryption Key / KEK): 256-bit key managed via KMS/environment.
- Data Encryption Key (DEK): 256-bit ephemeral key generated per-encryption.
- Authenticated Cipher: AES-256-GCM with 96-bit (12-byte) random nonce and 128-bit tag.
- Envelope Structure:
    env:<key_id>:<b64_dek_nonce>:<b64_encrypted_dek>:<b64_data_nonce>:<b64_encrypted_payload>
- Key Versioning: Supports CURRENT_KEY and historical OLD_KEYS for zero-downtime rotation.
"""

import os
import json
import base64
from typing import Dict, Optional, Tuple
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from app.core.config import settings


class KeyManagementError(Exception):
    """Raised when encryption or decryption keys are missing or invalid."""
    pass


class DecryptionError(Exception):
    """Raised when ciphertext authentication or decryption fails."""
    pass


class EnvelopeEncryptionService:
    """Enterprise-grade envelope encryption service with key versioning and rotation."""

    def __init__(
        self,
        current_key_id: Optional[str] = None,
        current_key_b64: Optional[str] = None,
        old_keys_map: Optional[Dict[str, str]] = None
    ) -> None:
        self.current_key_id = current_key_id or settings.ENCRYPTION_KEY_ID
        raw_current_key = current_key_b64 or settings.ENCRYPTION_KEY_AES256

        self._keys: Dict[str, bytes] = {}
        if raw_current_key:
            self._keys[self.current_key_id] = self._decode_key(raw_current_key)

        # Load old historical keys for seamless rotation
        raw_old = old_keys_map
        if raw_old is None and settings.ENCRYPTION_OLD_KEYS_JSON:
            try:
                raw_old = json.loads(settings.ENCRYPTION_OLD_KEYS_JSON)
            except Exception:
                raw_old = {}

        if raw_old:
            for kid, b64_key in raw_old.items():
                self._keys[kid] = self._decode_key(b64_key)

    @staticmethod
    def _decode_key(b64_str: str) -> bytes:
        """Validates and decodes a 32-byte Base64 key."""
        try:
            raw = base64.b64decode(b64_str)
            if len(raw) != 32:
                raise ValueError(f"Key length must be 32 bytes for AES-256, got {len(raw)} bytes.")
            return raw
        except Exception as e:
            raise KeyManagementError(f"Invalid Base64 AES-256 key: {e}") from e

    @staticmethod
    def generate_new_key_b64() -> Tuple[str, str]:
        """Generates a cryptographically secure 256-bit key in base64 format."""
        raw = AESGCM.generate_key(bit_length=256)
        return "v" + os.urandom(4).hex(), base64.b64encode(raw).decode("utf-8")

    def encrypt(self, plaintext: str) -> str:
        """
        Encrypts plaintext string using envelope encryption.
        Returns canonical envelope token string.
        """
        if not plaintext:
            return ""

        if self.current_key_id not in self._keys:
            raise KeyManagementError(f"Current master key '{self.current_key_id}' is not loaded.")

        kek = self._keys[self.current_key_id]
        plaintext_bytes = plaintext.encode("utf-8")

        # 1. Generate ephemeral Data Encryption Key (DEK)
        dek = AESGCM.generate_key(bit_length=256)

        # 2. Encrypt plaintext payload with DEK using AES-256-GCM
        data_nonce = os.urandom(12)
        encrypted_payload = AESGCM(dek).encrypt(data_nonce, plaintext_bytes, associated_data=None)

        # 3. Encrypt DEK with Master KEK using AES-256-GCM
        dek_nonce = os.urandom(12)
        associated_data = self.current_key_id.encode("utf-8")
        encrypted_dek = AESGCM(kek).encrypt(dek_nonce, dek, associated_data=associated_data)

        # 4. Form canonical envelope token
        token = (
            f"env:{self.current_key_id}:"
            f"{base64.b64encode(dek_nonce).decode('utf-8')}:"
            f"{base64.b64encode(encrypted_dek).decode('utf-8')}:"
            f"{base64.b64encode(data_nonce).decode('utf-8')}:"
            f"{base64.b64encode(encrypted_payload).decode('utf-8')}"
        )
        return token

    def decrypt(self, envelope_token: str) -> str:
        """
        Decrypts an envelope token using the appropriate key version.
        Supports both envelope ('env:...') and legacy direct AES-GCM ('enc:...').
        """
        if not envelope_token:
            return ""

        parts = envelope_token.split(":")
        if len(parts) < 3:
            raise DecryptionError("Malformed encrypted token structure.")

        prefix = parts[0]
        if prefix == "env":
            if len(parts) != 6:
                raise DecryptionError("Malformed envelope token structure: expected 6 colon-separated parts.")

            key_id = parts[1]
            if key_id not in self._keys:
                raise KeyManagementError(
                    f"Decryption key '{key_id}' not found in active or historical key ring."
                )

            kek = self._keys[key_id]
            try:
                dek_nonce = base64.b64decode(parts[2])
                encrypted_dek = base64.b64decode(parts[3])
                data_nonce = base64.b64decode(parts[4])
                encrypted_payload = base64.b64decode(parts[5])

                # Decrypt DEK using KEK
                associated_data = key_id.encode("utf-8")
                dek = AESGCM(kek).decrypt(dek_nonce, encrypted_dek, associated_data=associated_data)

                # Decrypt payload using DEK
                plaintext_bytes = AESGCM(dek).decrypt(data_nonce, encrypted_payload, associated_data=None)
                return plaintext_bytes.decode("utf-8")
            except Exception as e:
                raise DecryptionError(f"Envelope decryption or authentication failed: {e}") from e

        elif prefix == "enc":
            # Legacy or direct AES-GCM: enc:<key_id>:<nonce>:<ciphertext>
            if len(parts) != 4:
                raise DecryptionError("Malformed direct ciphertext structure.")

            key_id = parts[1]
            if key_id not in self._keys:
                raise KeyManagementError(f"Decryption key '{key_id}' not found.")

            kek = self._keys[key_id]
            try:
                nonce = base64.b64decode(parts[2])
                ciphertext = base64.b64decode(parts[3])
                plaintext_bytes = AESGCM(kek).decrypt(nonce, ciphertext, associated_data=None)
                return plaintext_bytes.decode("utf-8")
            except Exception as e:
                raise DecryptionError(f"Direct AES-GCM decryption failed: {e}") from e

        else:
            raise DecryptionError(f"Unsupported encryption scheme prefix '{prefix}'.")

    def reencrypt(self, envelope_token: str, target_key_id: Optional[str] = None) -> str:
        """
        Decrypts an envelope token with its existing key version and re-encrypts
        it with the target key version (defaults to current_key_id).
        """
        plaintext = self.decrypt(envelope_token)
        if target_key_id and target_key_id != self.current_key_id:
            # Temporarily encrypt under target key
            saved_id = self.current_key_id
            self.current_key_id = target_key_id
            try:
                return self.encrypt(plaintext)
            finally:
                self.current_key_id = saved_id
        return self.encrypt(plaintext)


# Global singleton encryption service instance
crypto_service = EnvelopeEncryptionService()
