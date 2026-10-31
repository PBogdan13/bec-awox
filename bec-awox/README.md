# Becul Meu

Aplicație Android pentru becurile AwoX (mesh Bluetooth Telink), fără aplicația AwoX.

- pornit/oprit, alb cald–rece, luminozitate, roată de culori, cele 7 scene din bec
- dală în meniul rapid pentru pornit/oprit
- se reconectează singură la becul ales

## Instalare

Ia `BeculMeu.apk` de la **Releases → latest**, deschide-l pe telefon și permite instalarea.
GitHub construiește automat un .apk nou la fiecare modificare a codului.

## Prima folosire

1. Resetează becul la datele din fabrică (nume mesh `unpaired`, parolă `1234`).
2. Deschide aplicația, permite „Dispozitive din apropiere”, apasă **Caută becul** și alege-l din listă.
3. Opțional: trage meniul rapid în jos, apasă creionul și adaugă dala **Bec**.

Protocolul și criptarea sunt după [python-awox-mesh-light](https://github.com/Leiaz/python-awox-mesh-light).
