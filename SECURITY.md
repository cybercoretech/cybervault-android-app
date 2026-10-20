# Security policy

## Reporting a vulnerability

Email **security@cybercoretech.net** with steps to reproduce. Please don't open a public issue for a vulnerability. You'll get an acknowledgement within a few days.

## Scope

In scope: the app, the Rust bridge in `rust/`, the release workflow, and `docs/install.sh`. Vault-format or crypto issues usually belong to [cybercoretech/cybervault](https://github.com/cybercoretech/cybervault), and generator issues to [cybercoretech/keysmith](https://github.com/cybercoretech/keysmith); report them there or here and we'll route them.

## Verifying a release

- `cybervault.apk.sha256` sits beside every APK.
- Release signing certificate SHA-256: `9dfa3699b793ad16af999b2fbe99b4b13ee26f8014705cc661521ee2b15e52cb`
- Check it with: `apksigner verify --print-certs cybervault.apk`

See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for what CyberVault does and doesn't defend against.
