# OBS package of FluidMessages Web for Fedora and openSUSE (home:lin8x/fluidmessages-web), built from source.
# The source comes from _service (obs_scm), and scripts/release sets the version below for each release. It runs
# with the distribution's own Python libraries, so OMEMO encryption for XMPP isn't available in it.
Name:           fluidmessages-web
Version:        0.50.0
Release:        0
Summary:        Your phone's texts, calls, and chats on your computer
License:        Apache-2.0
URL:            https://github.com/Lin8x/FluidMessages
Source0:        fluidmessages-web-%{version}.tar.gz
BuildArch:      noarch
BuildRequires:  desktop-file-utils
Requires:       python3
Requires:       python3-aiohttp
%if 0%{?suse_version}
Requires:       python3-tk
%else
Requires:       python3-tkinter
%endif
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
install -Dm 644 desktop/linux/io.github.Lin8x.FluidMessages.metainfo.xml %{buildroot}%{_datadir}/metainfo/io.github.Lin8x.FluidMessages.metainfo.xml
install -Dm 644 server/icons/icon-512.png %{buildroot}%{_datadir}/icons/hicolor/512x512/apps/io.github.Lin8x.FluidMessages.png

%check
desktop-file-validate %{buildroot}%{_datadir}/applications/io.github.Lin8x.FluidMessages.desktop

%files
%license LICENSE
%dir %{_datadir}/metainfo
%dir %{_datadir}/icons/hicolor
%dir %{_datadir}/icons/hicolor/512x512
%dir %{_datadir}/icons/hicolor/512x512/apps
%{_bindir}/%{name}
%{_datadir}/%{name}/
%{_datadir}/applications/io.github.Lin8x.FluidMessages.desktop
%{_datadir}/metainfo/io.github.Lin8x.FluidMessages.metainfo.xml
%{_datadir}/icons/hicolor/512x512/apps/io.github.Lin8x.FluidMessages.png

%changelog
* Fri Nov 27 2026 Lin8x <Lin8x@users.noreply.github.com> - 0.49.1-0
- Package FluidMessages Web for OBS
