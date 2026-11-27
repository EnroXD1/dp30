"""Create a local demo CA and server certificate. Keep the private key on the server."""
import argparse
import ipaddress
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def generate(folder, hosts):
    folder.mkdir(parents=True, exist_ok=True)
    names = ("ca.pem", "server.pem", "server.key")
    if any((folder / name).exists() for name in names):
        raise SystemExit("Сертификаты уже существуют. Укажите новую папку.")
    now = datetime.now(timezone.utc)

    def cert(label, key, issuer):
        return (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, label)]))
                .issuer_name(issuer).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=365))
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False))

    root_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "DP30 Local Demo CA")])
    ca = (cert("DP30 Local Demo CA", root_key, issuer)
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(key_usage(is_ca=True), critical=True)
          .sign(root_key, hashes.SHA256()))
    alt_names = []
    for host in hosts:
        try:
            alt_names.append(x509.IPAddress(ipaddress.ip_address(host)))
        except ValueError:
            alt_names.append(x509.DNSName(host))
    server_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    server = (cert(hosts[0], server_key, issuer).add_extension(x509.SubjectAlternativeName(alt_names), critical=False)
              .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(root_key.public_key()), critical=False)
              .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
              .add_extension(key_usage(is_ca=False), critical=True)
              .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
              .sign(root_key, hashes.SHA256()))
    (folder / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (folder / "server.pem").write_bytes(server.public_bytes(serialization.Encoding.PEM))
    (folder / "server.key").write_bytes(server_key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


def key_usage(*, is_ca):
    return x509.KeyUsage(
        digital_signature=not is_ca, content_commitment=False, key_encipherment=not is_ca,
        data_encipherment=False, key_agreement=False, key_cert_sign=is_ca, crl_sign=is_ca,
        encipher_only=False, decipher_only=False,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    parser.add_argument("hosts", nargs="*", default=["localhost", "127.0.0.1", "10.0.2.2"])
    args = parser.parse_args()
    generate(args.folder, args.hosts)
