import re
import sys
from pathlib import Path


def get_scalar(var: str, src: str) -> str | None:
    m = re.search(rf'^{var}=[\'"]([^\'"]*)[\'"]', src, re.MULTILINE)
    return m.group(1) if m else None


def get_array(var: str, src: str) -> list[str]:
    m = re.search(rf'^{var}=\((.*?)\)', src, re.DOTALL | re.MULTILINE)
    if not m:
        return []
    return [g1 if g1 else g2 for g1, g2 in re.findall(r"'(.*?)'|\"(.*?)\"", m.group(1))]


def main() -> None:
    repo_dir = Path(sys.argv[1])
    version = sys.argv[2]
    tarball_sha = sys.argv[3]

    pkgbuild_path = repo_dir / "PKGBUILD"
    srcinfo_path = repo_dir / ".SRCINFO"

    text = pkgbuild_path.read_text()

    text, n = re.subn(r'^pkgver=.*$', f'pkgver={version}', text, count=1, flags=re.MULTILINE)
    if n != 1:
        raise SystemExit('Failed to update pkgver in PKGBUILD')

    text, n = re.subn(
        r"(sha256sums=\(\s*\n\s*')[^']+(')",
        rf"\1{tarball_sha}\2",
        text, count=1, flags=re.MULTILINE,
    )
    if n != 1:
        raise SystemExit('Failed to update sha256sums in PKGBUILD')

    pkgbuild_path.write_text(text)

    pkgname = get_scalar('pkgname', text) or 'tritium-launcher-bin'
    pkgver_resolved = get_scalar('pkgver', text) or version
    pkgrel_resolved = get_scalar('pkgrel', text) or '1'

    def resolve_vars(val: str) -> str:
        subs = {'pkgname': pkgname, 'pkgver': pkgver_resolved, 'pkgrel': pkgrel_resolved}
        for k, v in subs.items():
            val = val.replace(f'${{{k}}}', v).replace(f'${k}', v)
        return val

    lines = [
        '# Generated from PKGBUILD',
        f'pkgbase = {pkgname}',
        f'pkgname = {pkgname}',
        f'pkgver = {get_scalar("pkgver", text) or version}',
        f'pkgrel = {get_scalar("pkgrel", text) or "1"}',
    ]

    for var in ('pkgdesc', 'url', 'install'):
        v = get_scalar(var, text)
        if v:
            lines.append(f'{var} = {v}')

    for var in ('arch', 'license', 'groups', 'checkdepends', 'makedepends',
                'optdepends', 'provides', 'conflicts', 'replaces', 'depends'):
        for val in get_array(var, text):
            lines.append(f'{var} = {resolve_vars(val)}')

    for var in ('source', 'noextract', 'md5sums', 'sha1sums', 'sha256sums',
                'sha384sums', 'sha512sums', 'b2sums'):
        for val in get_array(var, text):
            lines.append(f'{var} = {resolve_vars(val)}')

    srcinfo_path.write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
