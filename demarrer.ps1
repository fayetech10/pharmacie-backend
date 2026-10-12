# Demarrage de la plateforme SEN-CSU en local (PostgreSQL), hors Docker.
#   .\demarrer.ps1              demarre les 7 applications
#   .\demarrer.ps1 -Arreter     arrete tout
#   .\demarrer.ps1 -Etat        affiche l'etat
#
# Alternative tout-en-un, base comprise : docker compose up -d --build (cf. racine).
param(
    [switch]$Arreter,
    [switch]$Etat
)

$ErrorActionPreference = "Stop"
$racine = $PSScriptRoot
$journaux = Join-Path $racine ".journaux"
$donnees = Join-Path $racine ".donnees"

# Port et URL de la base PostgreSQL partagée (Docker postgres-csu)
$portBase = 5432
$urlBase = "jdbc:postgresql://localhost:$portBase/pharmacie_csu?stringtype=unspecified"

# Le secret DOIT être identique pour tous les services : c'est lui qui permet à chacun
# de valider localement un jeton émis par service-identite.
$env:APP_JWT_SECRET = "developpement-local-sencsu-secret-partage-32c"
$env:SPRING_DATASOURCE_URL = $urlBase
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = "org.postgresql.Driver"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "postgres"

# L'ordre compte : identite pose le schema, la passerelle ferme la marche.
#
# Le coeur EST necessaire : patients, lettres de garantie, bons de commande,
# feuilles de soins, statistiques... ne sont pas encore portes vers les
# microservices, et la passerelle lui renvoie ces familles d'URL. Sur 8090, parce
# qu'il ecoute 8080 par defaut et que cette place revient a la passerelle.
#
# Artefact : <module>/target/<artifactId>-0.0.1-SNAPSHOT.jar. Le coeur fait
# exception, son artifactId est « pharmacie » et non « coeur ».
$services = @(
    @{ Nom = "identite";     Module = "service-identite";     Port = 8081 },
    @{ Nom = "referentiel";  Module = "service-referentiel";  Port = 8082 },
    @{ Nom = "pharmacie";    Module = "service-pharmacie";    Port = 8083 },
    @{ Nom = "poste-sante";  Module = "service-poste-sante";  Port = 8084 },
    @{ Nom = "centre-sante"; Module = "service-centre-sante"; Port = 8085 },
    @{ Nom = "coeur";        Module = "coeur";                Port = 8090; Artefact = "pharmacie" },
    @{ Nom = "gateway";      Module = "api-gateway";          Port = 8080 }
)

function Get-Pid($port) {
    $ligne = netstat -ano | Select-String ":$port\s.*LISTENING"
    if ($ligne) { return ($ligne[0] -split '\s+')[-1] }
    return $null
}

function Attendre-Port($port, $secondes) {
    $essais = 0
    while (-not (Get-Pid $port) -and $essais -lt ($secondes * 2)) {
        Start-Sleep -Milliseconds 500
        $essais++
    }
    return [bool](Get-Pid $port)
}

if ($Etat) {
    $processus = Get-Pid $portBase
    $statut = if ($processus) { "UP   (pid $processus)" } else { "arretee" }
    Write-Host ("{0,-14} {1,-6} {2}" -f "PostgreSQL", $portBase, $statut)
    foreach ($s in $services) {
        $processus = Get-Pid $s.Port
        $statut = if ($processus) { "UP   (pid $processus)" } else { "arrete" }
        Write-Host ("{0,-14} {1,-6} {2}" -f $s.Nom, $s.Port, $statut)
    }
    return
}

if ($Arreter) {
    foreach ($s in $services) {
        $processus = Get-Pid $s.Port
        if ($processus) {
            Stop-Process -Id $processus -Force
            Write-Host "Arrete : $($s.Nom) (port $($s.Port))"
        }
    }
    foreach ($port in ($services | ForEach-Object { $_.Port })) {
        $essais = 0
        while ((Get-Pid $port) -and $essais -lt 20) { Start-Sleep -Milliseconds 500; $essais++ }
    }
    return
}

if (-not (Test-Path $journaux)) { New-Item -ItemType Directory $journaux | Out-Null }
if (-not (Test-Path $donnees)) { New-Item -ItemType Directory $donnees | Out-Null }

# 1. Base PostgreSQL partagere
if (Get-Pid $portBase) {
    Write-Host "Base PostgreSQL disponible sur le port $portBase (conteneur postgres-csu)"
} else {
    Write-Host "Base PostgreSQL non detectee sur le port $portBase." -ForegroundColor Yellow
}

$javaCmd = (Get-Command java).Source

# 2. Les services
foreach ($s in $services) {
    $artefact = if ($s.Artefact) { $s.Artefact } else { $s.Module }
    $jar = Join-Path $racine (Join-Path $s.Module (Join-Path "target" "$artefact-0.0.1-SNAPSHOT.jar"))
    if (-not (Test-Path $jar)) {
        Write-Host "Jar absent : $jar - lancez d'abord 'mvn -DskipTests clean install'." -ForegroundColor Red
        return
    }
    if (Get-Pid $s.Port) {
        Write-Host "Deja demarre : $($s.Nom) (port $($s.Port))"
        continue
    }
    $sortie = Join-Path $journaux "$($s.Nom).log"

    # Le schema a UN SEUL proprietaire : l'identite. Les autres n'emettent aucun DDL.
    # Les sept applications declarent pourtant les memes entites (@EntityScan sur
    # com.csu.pharmacie.entity, dans « commun ») : ce que l'identite pose leur suffit.
    # Les laisser toutes en "update" fait echouer l'une d'elles au demarrage, Hibernate
    # introspectant un schema qu'une autre modifie au meme instant.
    $env:JPA_DDL_AUTO = if ($s.Nom -eq "identite") { "update" } else { "none" }

    # --server.port impose le port du tableau ci-dessus : une seule source de verite,
    # le script ne peut plus diverger du defaut ecrit dans application.yml.
    Start-Process -FilePath $javaCmd `
        -ArgumentList @("-jar", "`"$jar`"", "--server.port=$($s.Port)") `
        -WorkingDirectory (Join-Path $racine $s.Module) `
        -RedirectStandardOutput $sortie `
        -RedirectStandardError (Join-Path $journaux "$($s.Nom).err.log") `
        -WindowStyle Hidden
    Write-Host "Demarre : $($s.Nom) sur le port $($s.Port) - journal : $sortie"

    # Avec ddl-auto=update, sept Hibernate qui creent le meme schema a la meme
    # seconde se genent. On laisse l'identite poser les tables : les suivants les
    # trouvent en place et n'emettent plus aucun DDL.
    if ($s.Nom -eq "identite") {
        Write-Host "  ... attente de l'identite (creation du schema)"
        [void](Attendre-Port $s.Port 90)
    }
}

Write-Host ""
Write-Host "Etat complet : http://localhost:8080/api/health (quelques secondes de demarrage)."
