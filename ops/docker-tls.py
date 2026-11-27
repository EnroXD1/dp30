"""Maintain a private, persistent demo CA and issue a leaf for the selected IPv4."""
import ipaddress
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def name(label):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, label)])


def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def private_bytes(k):
    return k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                           serialization.NoEncryption())


def save(path, data, mode=0o600):
    tmp = path.with_suffix(path.suffix + '.tmp')
    with open(tmp, 'wb') as f:
        f.write(data)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


def builder(subject, issuer, k, now, expires):
    return (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer)
            .public_key(k.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5)).not_valid_after(expires)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(k.public_key()), critical=False))


def pub(k):
    return k.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def ensure(folder, address):
    ip = ipaddress.IPv4Address(address)
    folder.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)
    ca_path, ca_key_path = folder / 'ca.pem', folder / 'ca.key'
    leaf_path, leaf_key_path = folder / 'server.pem', folder / 'server.key'
    if ca_path.exists() != ca_key_path.exists():
        raise RuntimeError('Incomplete CA pair; restore both files from backup. No automatic CA replacement.')
    if not ca_path.exists():
        if leaf_path.exists() or leaf_key_path.exists():
            raise RuntimeError('CA missing but leaf exists; restore CA files first.')
        ca_key = key()
        n = name('DP30 Docker Local CA')
        ca = (builder(n, n, ca_key, now, now + timedelta(days=3650))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
              .sign(ca_key, hashes.SHA256()))
        save(ca_key_path, private_bytes(ca_key))
        save(ca_path, ca.public_bytes(serialization.Encoding.PEM), 0o644)
    else:
        ca = x509.load_pem_x509_certificate(ca_path.read_bytes())
        ca_key = serialization.load_pem_private_key(ca_key_path.read_bytes(), password=None)
        if pub(ca.public_key()) != pub(ca_key.public_key()):
            raise RuntimeError('CA certificate and key do not match.')
        if not ca.extensions.get_extension_for_class(x509.BasicConstraints).value.ca:
            raise RuntimeError('Invalid CA certificate.')
        ca.verify_directly_issued_by(ca)
    if not ca.not_valid_before_utc <= now < ca.not_valid_after_utc - timedelta(days=7):
        raise RuntimeError('CA expired or expiring; replace CA intentionally and reinstall public CA on phones.')
    hosts = [x509.DNSName('localhost'), x509.IPAddress(ipaddress.IPv4Address('127.0.0.1')),
             x509.IPAddress(ipaddress.IPv4Address('10.0.2.2'))]
    if x509.IPAddress(ip) not in hosts:
        hosts.append(x509.IPAddress(ip))
    reuse = False
    if leaf_path.exists() and leaf_key_path.exists():
        try:
            leaf = x509.load_pem_x509_certificate(leaf_path.read_bytes())
            leaf_key = serialization.load_pem_private_key(leaf_key_path.read_bytes(), password=None)
            leaf.verify_directly_issued_by(ca)
            reuse = (pub(leaf.public_key()) == pub(leaf_key.public_key())
                     and leaf.not_valid_before_utc <= now < leaf.not_valid_after_utc - timedelta(days=7)
                     and all(h in leaf.extensions.get_extension_for_class(x509.SubjectAlternativeName).value for h in hosts))
        except Exception:
            # A damaged/old leaf is safe to replace; the CA must stay the same.
            reuse = False
    if not reuse:
        leaf_key = key()
        leaf = (builder(name(str(ip)), ca.subject, leaf_key, now,
                        min(now + timedelta(days=365), ca.not_valid_after_utc))
                .add_extension(x509.SubjectAlternativeName(hosts), critical=False)
                .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .add_extension(x509.KeyUsage(True, False, True, False, False, False, False, False, False), critical=True)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .sign(ca_key, hashes.SHA256()))
        save(leaf_key_path, private_bytes(leaf_key))
        save(leaf_path, leaf.public_bytes(serialization.Encoding.PEM), 0o644)
    # DER extension is convenient for Android's certificate picker.
    save(folder / 'DP30-CA.crt', ca.public_bytes(serialization.Encoding.DER), 0o644)
    print('TLS ready; CA SHA-256:', ca.fingerprint(hashes.SHA256()).hex())


if __name__ == '__main__':
    ensure(Path('/certs'), sys.argv[1])
