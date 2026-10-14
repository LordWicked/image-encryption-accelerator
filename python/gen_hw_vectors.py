#!/usr/bin/env python3
import ascon
import os

def encrypt_file(bin_filepath, key_hex, nonce_hex, ad_bytes, output_enc_filepath):
    """
    Reads a raw binary file, encrypts it using ASCON-AEAD128, and writes the encrypted output.
    Returns the plaintext bytes for verification purposes.
    """
    print(f"\n{'='*60}")
    print(f"ENCRYPTION PHASE")
    print(f"{'='*60}")
    
    # 1. Read the raw binary file
    if not os.path.exists(bin_filepath):
        print(f"ERROR: File not found: {bin_filepath}")
        print("Please ensure you have generated the .bin file first (e.g., using your png_to_bin script).")
        return None
        
    with open(bin_filepath, "rb") as f:
        pt_bytes = f.read()
        
    print(f"[*] Read {len(pt_bytes)} bytes from {bin_filepath}")
    
    # 2. Prepare Key, Nonce, and Associated Data (Metadata)
    key = bytes.fromhex(key_hex)
    nonce = bytes.fromhex(nonce_hex)
    
    print(f"KEY  : {key.hex()}")
    print(f"NONCE: {nonce.hex()}")
    print(f"AD   : {ad_bytes.hex()}  (ASCII: '{ad_bytes.decode('utf-8', errors='replace')}')")
    
    # 3. Encrypt using ASCON-AEAD128
    # Note: ascon_encrypt returns (ciphertext + 16-byte tag appended at the end)
    ct_with_tag = ascon.ascon_encrypt(
        key=key,
        nonce=nonce,
        associateddata=ad_bytes,
        plaintext=pt_bytes,
        variant="Ascon-AEAD128"
    )
    
    # 4. Split Ciphertext and Tag for easy HW comparison
    ct_bytes = ct_with_tag[:-16]
    tag_bytes = ct_with_tag[-16:]
    
    # 5. Print Summary (First and last 32 bytes to avoid console spam)
    print(f"\n--- Plaintext Snippet (First 32 bytes) ---")
    print(pt_bytes[:32].hex())
    if len(pt_bytes) > 64:
        print(f"... ({len(pt_bytes) - 64} bytes omitted) ...")
        print(f"--- Plaintext Snippet (Last 32 bytes) ---")
        print(pt_bytes[-32:].hex())
    
    print(f"\n--- Ciphertext Snippet (First 32 bytes) ---")
    print(ct_bytes[:32].hex())
    if len(ct_bytes) > 64:
        print(f"... ({len(ct_bytes) - 64} bytes omitted) ...")
        print(f"--- Ciphertext Snippet (Last 32 bytes) ---")
        print(ct_bytes[-32:].hex())
    
    print(f"\nTAG  : {tag_bytes.hex()}")
    
    # 6. Write the "Golden Reference" output file
    # Format: [16-byte Plain AD] + [Encrypted Pixels] + [16-byte Tag]
    with open(output_enc_filepath, "wb") as f:
        f.write(ad_bytes)          # Plain metadata header (readable, but authenticated)
        f.write(ct_with_tag)       # Encrypted payload + tag
        
    total_size = len(ad_bytes) + len(ct_with_tag)
    print(f"\n[OK] Wrote encrypted file to: {output_enc_filepath}")
    print(f"     (Total Size: {total_size} bytes)")
    
    return pt_bytes


def decrypt_file(encrypted_filepath, key_hex, nonce_hex, expected_ad_length, original_pt_bytes=None):
    """
    Reads an encrypted file, decrypts it using ASCON-AEAD128, and verifies correctness.
    Optionally compares against original plaintext if provided.
    """
    print(f"\n{'='*60}")
    print(f"DECRYPTION PHASE")
    print(f"{'='*60}")
    
    if not os.path.exists(encrypted_filepath):
        print(f"ERROR: Encrypted file not found: {encrypted_filepath}")
        return False
    
    # 1. Read the encrypted file
    with open(encrypted_filepath, "rb") as f:
        file_ad = f.read(expected_ad_length)
        file_ct_with_tag = f.read()
    
    print(f"[*] Read {len(file_ad)} bytes of metadata from {encrypted_filepath}")
    print(f"[*] Read {len(file_ct_with_tag)} bytes of ciphertext+tag")
    
    # 2. Prepare Key and Nonce
    key = bytes.fromhex(key_hex)
    nonce = bytes.fromhex(nonce_hex)
    
    print(f"KEY  : {key.hex()}")
    print(f"NONCE: {nonce.hex()}")
    print(f"AD   : {file_ad.hex()}  (ASCII: '{file_ad.decode('utf-8', errors='replace')}')")
    
    # 3. Decrypt using ASCON-AEAD128
    decrypted_pt = ascon.ascon_decrypt(
        key=key,
        nonce=nonce,
        associateddata=file_ad,
        ciphertext=file_ct_with_tag,
        variant="Ascon-AEAD128"
    )
    
    if decrypted_pt is None:
        print("[FAIL] Decryption FAILED! Tag verification error.")
        return False
    
    print(f"\n--- Decrypted Plaintext Snippet (First 32 bytes) ---")
    print(decrypted_pt[:32].hex())
    if len(decrypted_pt) > 64:
        print(f"... ({len(decrypted_pt) - 64} bytes omitted) ...")
        print(f"--- Decrypted Plaintext Snippet (Last 32 bytes) ---")
        print(decrypted_pt[-32:].hex())
    
    # 4. Verify against original if provided
    if original_pt_bytes is not None:
        if decrypted_pt == original_pt_bytes:
            print(f"\n[OK] Decryption verification PASSED. Decrypted data matches original .bin file exactly.")
            return True
        else:
            print(f"\n[FAIL] Decryption verification FAILED! Decrypted data does not match original.")
            return False
    else:
        print(f"\n[OK] Decryption completed successfully (no original data provided for comparison).")
        return True


if __name__ == "__main__":
    # --- CONFIGURATION ---

    INPUT_BIN_FILE = "../assets/pixelartbin.txt" 
    OUTPUT_ENC_FILE = "../assets/secure_image.enc"
    
    # 16-byte (128-bit) Key and Nonce
    TEST_KEY_HEX = "000102030405060708090a0b0c0d0e0f"
    TEST_NONCE_HEX = "101112131415161718191a1b1c1d1e1f"
    
    # 16-byte Associated Data (Metadata)
    TEST_AD_BYTES = b"IMG:128x128:GRAY"
    AD_LENGTH = len(TEST_AD_BYTES)
    
    # Phase 1: Encrypt the file
    original_plaintext = encrypt_file(
        bin_filepath=INPUT_BIN_FILE,
        key_hex=TEST_KEY_HEX,
        nonce_hex=TEST_NONCE_HEX,
        ad_bytes=TEST_AD_BYTES,
        output_enc_filepath=OUTPUT_ENC_FILE
    )
    
    decrypt_file(
            encrypted_filepath=OUTPUT_ENC_FILE,
            key_hex=TEST_KEY_HEX,
            nonce_hex=TEST_NONCE_HEX,
            expected_ad_length=AD_LENGTH,
            original_pt_bytes=original_plaintext
        )