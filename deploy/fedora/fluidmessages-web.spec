# Fedora package of FluidMessages Web, the computer app, built from source in the lin8x/fluidmessages COPR.
# .copr/Makefile fills in the version and release from the latest git tag. Unlike the .rpm attached to each GitHub
# release (the packaged program, with everything inside), this one runs the source with Fedora's own Python
# libraries. OMEMO encryption for XMPP needs Python libraries that Fedora doesn't package, so it's off here.
Name:           fluidmessages-web
Version:        @VERSION@
Release:        @RELEASE@%{?dist}
Summary:        Your phone's texts, calls, and chats on your computer
License:        Apache-2.0
URL:            https://github.com/Lin8x/FluidMessages
Source0:        fluidmessages-web-%{version}.tar.gz
BuildArch:      noarch
BuildRequires:  desktop-file-utils
Requires:       python3
Requires:       python3-aiohttp
Requires:       python3-tkinter
Requires:       openssl
Requires:       xdg-utils
Recommends:     python3-slixmpp
Recommends:     python3-matrix-nio

%description
FluidMessages Web runs on your computer and shows your Android phone's texts, calls, and chats in an app window.
Your phone pairs with it over your own network, and nothing goes through a cloud service. It can also connect to
Matrix, XMPP and IRC.

%prep
%autosetup -n fluidmessages-web-%{version}

%build

%install
install -d %{buildroot}%{_datadir}/%{name}/desktop
cp -a server %{buildroot}%{_datadir}/%{name}/
rm -rf %{buildroot}%{_datadir}/%{name}/server/__pycache__
rm -f %{buildroot}%{_datadir}/%{name}/server/Dockerfile
install -m 644 desktop/launcher.py %{buildroot}%{_datadir}/%{name}/desktop/
install -d %{buildroot}%{_bindir}
printf '#!/bin/sh\nexec /usr/bin/python3 %{_datadir}/%{name}/desktop/launcher.py "$@"\n' > %{buildroot}%{_bindir}/%{name}
chmod 755 %{buildroot}%{_bindir}/%{name}
desktop-file-install --dir=%{buildroot}%{_datadir}/applications desktop/linux/io.github.Lin8x.FluidMessages.desktop
install -Dm 644 desktop/linux/io.github.Lin8x.FluidMessages.metainfo.xml %{buildroot}%{_metainfodir}/io.github.Lin8x.FluidMessages.metainfo.xml
install -Dm 644 server/icons/icon-512.png %{buildroot}%{_datadir}/icons/hicolor/512x512/apps/io.github.Lin8x.FluidMessages.png

%check
desktop-file-validate %{buildroot}%{_datadir}/applications/io.github.Lin8x.FluidMessages.desktop

%files
%license LICENSE
%{_bindir}/%{name}
%{_datadir}/%{name}/
%{_datadir}/applications/io.github.Lin8x.FluidMessages.desktop
%{_metainfodir}/io.github.Lin8x.FluidMessages.metainfo.xml
%{_datadir}/icons/hicolor/512x512/apps/io.github.Lin8x.FluidMessages.png

%changelog
* Thu Nov 26 2026 Lin8x <Lin8x@users.noreply.github.com> - 0.48.0-1
- Package FluidMessages Web for Fedora
