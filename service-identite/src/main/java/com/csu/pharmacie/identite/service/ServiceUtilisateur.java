package com.csu.pharmacie.identite.service;

import com.csu.pharmacie.entity.TypeStructure;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.identite.depot.DepotStructure;
import com.csu.pharmacie.identite.depot.DepotUtilisateur;
import com.csu.pharmacie.identite.dto.ResultatImport;
import com.csu.pharmacie.entity.District;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Cycle de vie des comptes (§1 : rattachement à une structure, activation, désactivation).
 */
@Service
@RequiredArgsConstructor
public class ServiceUtilisateur {

    private final DepotUtilisateur depotUtilisateur;
    private final DepotStructure depotStructure;
    private final PasswordEncoder encodeur;

    public List<User> lister(Role role, String structureId, String districtId) {
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();

        return depotUtilisateur.findAll().stream()
                // Le service régional n'administre que les comptes de sa région.
                .filter(u -> courant.getRole() != Role.SERVICE_REGIONAL
                        || courant.getRegionId() == null
                        || courant.getRegionId().equals(u.getRegionId()))
                .filter(u -> role == null || u.getRole() == role)
                .filter(u -> structureId == null || structureId.isBlank()
                        || structureId.equals(u.getStructureSanitaireId()))
                .filter(u -> districtId == null || districtId.isBlank()
                        || districtId.equals(u.getDistrictId()))
                .sorted(Comparator.comparing(u -> (u.getNom() == null ? "" : u.getNom())))
                .toList();
    }

    public User parId(String id) {
        return depotUtilisateur.findById(id)
                .orElseThrow(() -> ExceptionMetier.introuvable("User introuvable : " + id));
    }

    /**
     * Création d'un compte. Le mot de passe fourni est facultatif : sans lui, le compte
     * reçoit le mot de passe par défaut ({@link #MOT_DE_PASSE_GENERIQUE}), renvoyé à
     * l'appelant, avec l'obligation de le changer à la première connexion.
     */
    @Transactional
    public CompteCree creer(User demande) {
        if (demande.getEmail() == null || demande.getEmail().isBlank()) {
            throw ExceptionMetier.invalide("L'email est obligatoire");
        }
        String email = demande.getEmail().trim().toLowerCase(Locale.ROOT);
        if (depotUtilisateur.existsByEmailIgnoreCase(email)) {
            throw ExceptionMetier.conflit("Un compte existe déjà avec l'email « " + email + " »");
        }
        if (demande.getRole() == null) {
            throw ExceptionMetier.invalide("Le rôle est obligatoire");
        }
        verifierRoleAttribuable(demande.getRole());
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        if (courant.getRole() == Role.SERVICE_REGIONAL
                && (demande.getRegionId() == null || demande.getRegionId().isBlank())) {
            // Comme le cœur : un compte créé par un service régional appartient à sa région.
            demande.setRegionId(courant.getRegionId());
        }
        verifierRattachement(demande);
        if (courant.getRole() == Role.SERVICE_REGIONAL) {
            // Après le rattachement, qui aligne la région sur celle de la structure.
            verifierRegion(demande.getRegionId());
        }

        boolean motDePasseGenere = demande.getPassword() == null || demande.getPassword().isBlank();
        String motDePasseClair = motDePasseGenere ? MOT_DE_PASSE_GENERIQUE : demande.getPassword();

        User utilisateur = User.builder()
                .nom(demande.getNom())
                .prenom(demande.getPrenom())
                .email(email)
                .username(genererIdentifiant(demande))
                .password(encodeur.encode(motDePasseClair))
                .role(demande.getRole())
                .structureSanitaireId(demande.getStructureSanitaireId())
                .districtId(demande.getDistrictId())
                .regionId(demande.getRegionId())
                .pharmacieId(demande.getPharmacieId())
                .actif(true)
                // Systématique, et non plus seulement quand le mot de passe est généré :
                // qu'il soit tiré au hasard ou saisi par l'administrateur, un tiers le
                // connaît. Le titulaire doit choisir le sien avant d'utiliser l'application
                // — c'est ce qui couvre les comptes BCSU créés avec un mot de passe imposé.
                .mustChangePassword(true)
                .build();

        User enregistre = depotUtilisateur.save(utilisateur);
        return new CompteCree(enregistre, motDePasseGenere ? motDePasseClair : null);
    }

    @Transactional
    public User modifier(String id, User modifications) {
        User utilisateur = parId(id);
        verifierAdministrable(utilisateur);
        if (modifications.getRole() != null) {
            // Sans ce contrôle, un service régional pouvait promouvoir un compte — le sien
            // compris — administrateur.
            verifierRoleAttribuable(modifications.getRole());
        }
        if (modifications.getNom() != null) {
            utilisateur.setNom(modifications.getNom());
        }
        if (modifications.getPrenom() != null) {
            utilisateur.setPrenom(modifications.getPrenom());
        }
        if (modifications.getRole() != null) {
            utilisateur.setRole(modifications.getRole());
        }
        utilisateur.setStructureSanitaireId(modifications.getStructureSanitaireId());
        utilisateur.setDistrictId(modifications.getDistrictId());
        utilisateur.setRegionId(modifications.getRegionId());
        utilisateur.setPharmacieId(modifications.getPharmacieId());
        verifierRattachement(utilisateur);
        if (ContexteUtilisateur.courant().getRole() == Role.SERVICE_REGIONAL) {
            // Un compte de la région ne peut pas en sortir par une modification.
            verifierRegion(utilisateur.getRegionId());
        }
        return depotUtilisateur.save(utilisateur);
    }

    /**
     * Activation / désactivation (§1). Un administrateur ne peut pas se désactiver
     * lui-même : c'est la façon la plus courante de se verrouiller hors de la plateforme.
     * Hors administrateur, seuls les comptes du périmètre de l'appelant sont concernés.
     */
    @Transactional
    public User basculerActivation(String id, boolean actif) {
        User utilisateur = parId(id);
        if (!actif && id.equals(ContexteUtilisateur.courant().getId())) {
            throw ExceptionMetier.invalide("Vous ne pouvez pas désactiver votre propre compte");
        }
        verifierAdministrable(utilisateur);
        utilisateur.setActif(actif);
        return depotUtilisateur.save(utilisateur);
    }

    /**
     * Réinitialisation par un administrateur : retour au mot de passe par défaut, à changer.
     * Ce mot de passe est renvoyé à l'appelant — d'où le contrôle de périmètre, sans lequel
     * il suffisait d'un compte régional pour obtenir l'accès d'un administrateur.
     */
    @Transactional
    public String reinitialiserMotDePasse(String id) {
        User utilisateur = parId(id);
        verifierAdministrable(utilisateur);
        String motDePasse = MOT_DE_PASSE_GENERIQUE;
        appliquerMotDePasseTemporaire(utilisateur, motDePasse);
        return motDePasse;
    }

    // ────────────────────────────── comptes de structure ──────────────────────────────

    /**
     * Comptes de connexion d'une structure, du plus ancien au plus récent. Les agents BCSU
     * rattachés à la structure portent aussi son identifiant, mais ne sont pas son accès :
     * seuls les rôles de structure comptent.
     */
    public List<User> comptesDeStructure(String structureId) {
        return depotUtilisateur.findByStructureSanitaireId(structureId).stream()
                .filter(u -> u.getRole() != null && u.getRole().estStructureSanitaire())
                .sorted(Comparator.comparing(User::getCreatedAt,
                                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(User::getId))
                .toList();
    }

    /** Le compte de connexion de la structure : son responsable s'il en est un, sinon le plus ancien. */
    public Optional<User> compteDeStructure(StructureSanitaire structure) {
        List<User> comptes = comptesDeStructure(structure.getId());
        return comptes.stream()
                .filter(u -> u.getId().equals(structure.getResponsableId()))
                .findFirst()
                .or(() -> comptes.stream().findFirst());
    }

    /** La structure et ses comptes vont de pair : la désactiver ferme leur accès, la réactiver le rouvre. */
    @Transactional
    public void basculerComptesStructure(String structureId, boolean actif) {
        for (User compte : comptesDeStructure(structureId)) {
            compte.setActif(actif);
            depotUtilisateur.save(compte);
        }
    }

    /**
     * Nouveau mot de passe temporaire pour le compte de connexion d'une structure — créé
     * s'il n'existe pas encore (structures importées, antérieures à la création
     * automatique) : le but est que la structure puisse se connecter, pas de constater
     * qu'elle ne le peut pas. Le périmètre est contrôlé par l'appelant, sur la structure.
     */
    @Transactional
    public AccesCompte reinitialiserCompteStructure(StructureSanitaire structure) {
        String motDePasse = MOT_DE_PASSE_GENERIQUE;
        Optional<User> existant = compteDeStructure(structure);
        if (existant.isPresent()) {
            User compte = existant.get();
            appliquerMotDePasseTemporaire(compte, motDePasse);
            return new AccesCompte(compte.getId(), identifiantDeConnexion(compte), motDePasse, false);
        }

        CompteCree cree = creerCompteStructure(structure, structure.getEmail(), motDePasse);
        if (cree == null) {
            throw ExceptionMetier.conflit("Le compte de « " + structure.getNom() + " » vient d'être créé : réessayez.");
        }
        User compte = cree.utilisateur();
        if (!structure.isActif()) {
            // Le compte suit la structure : pas d'accès ouvert sur une structure désactivée.
            compte.setActif(false);
            depotUtilisateur.save(compte);
        }
        return new AccesCompte(compte.getId(), identifiantDeConnexion(compte), motDePasse, true);
    }

    private void appliquerMotDePasseTemporaire(User compte, String motDePasse) {
        compte.setPassword(encodeur.encode(motDePasse));
        // Posé par un tiers, donc à remplacer par le titulaire à sa prochaine connexion.
        compte.setMustChangePassword(true);
        depotUtilisateur.save(compte);
    }

    /** Ce que l'agent saisit à la connexion : l'identifiant court, à défaut l'email. */
    private static String identifiantDeConnexion(User compte) {
        if (compte.getUsername() != null && !compte.getUsername().isBlank()) {
            return compte.getUsername();
        }
        return compte.getEmail() == null ? "" : compte.getEmail();
    }

    /**
     * Crée un compte pour chaque structure qui n'en a pas encore (§6 : création en masse).
     * Les identifiants générés et leurs mots de passe temporaires sont renvoyés une seule
     * fois, dans le compte rendu.
     */
    @Transactional
    public ResultatImport creerComptesStructures(String districtId) {
        ResultatImport resultat = new ResultatImport();
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        List<StructureSanitaire> structures = (districtId == null || districtId.isBlank()
                ? depotStructure.findAll()
                : depotStructure.findByDistrictId(districtId)).stream()
                // Les mots de passe sont renvoyés à l'appelant : un service régional ne doit
                // obtenir que ceux des structures de sa région.
                .filter(s -> courant.getRole() != Role.SERVICE_REGIONAL
                        || (courant.getRegionId() != null && courant.getRegionId().equals(s.getRegionId())))
                .toList();
        resultat.setLignesLues(structures.size());

        for (StructureSanitaire structure : structures) {
            if (!comptesDeStructure(structure.getId()).isEmpty()) {
                resultat.setIgnores(resultat.getIgnores() + 1);
                continue;
            }
            String identifiant = identifiantDisponible(structure.getCode());
            String email = identifiant.toLowerCase(Locale.ROOT) + "@sencsu.sn";
            if (depotUtilisateur.existsByEmailIgnoreCase(email)) {
                resultat.getErreurs().add("Structure « " + structure.getNom()
                        + " » : l'email généré " + email + " est déjà pris.");
                continue;
            }
            String motDePasse = MOT_DE_PASSE_GENERIQUE;

            depotUtilisateur.save(User.builder()
                    .nom(structure.getNom())
                    .prenom("")
                    .email(email)
                    .username(identifiant)
                    .password(encodeur.encode(motDePasse))
                    // Le rôle générique se résout à la connexion selon le type de structure.
                    .role(structure.typeEffectif() == com.csu.pharmacie.entity.TypeStructure.PS
                            ? Role.POSTE_SANTE : Role.CENTRE_SANTE)
                    .structureSanitaireId(structure.getId())
                    .districtId(structure.getDistrictId())
                    .regionId(structure.getRegionId())
                    .actif(true)
                    .mustChangePassword(true)
                    .build());

            resultat.setCrees(resultat.getCrees() + 1);
            resultat.getComptesCrees().add(structure.getNom() + " — identifiant : " + identifiant
                    + " · mot de passe : " + motDePasse);
        }
        return resultat;
    }

    // ────────────────────────────── règles ──────────────────────────────

    /**
     * L'appelant peut-il administrer ce compte (l'activer, le réinitialiser, le modifier) ?
     *
     * <p>On n'administre que des comptes de rang inférieur au sien, et le service régional
     * uniquement dans sa région. Sans cette règle, un service régional pouvait réinitialiser
     * le mot de passe d'un administrateur — le mot de passe temporaire lui étant renvoyé —
     * et prendre la main sur toute la plateforme.</p>
     */
    private void verifierAdministrable(User cible) {
        UtilisateurAuthentifie courant = ContexteUtilisateur.courant();
        if (courant.getRole() == Role.ADMIN) {
            return;
        }
        verifierRoleAttribuable(cible.getRole());
        if (courant.getRole() == Role.SERVICE_REGIONAL) {
            verifierRegion(cible.getRegionId());
        }
    }

    /**
     * Rôle que l'appelant peut attribuer, ou dont il peut administrer les comptes : tous pour
     * l'administrateur, tous sauf ADMIN pour le service central, et pour le service régional
     * les seuls comptes de terrain — jamais un rôle de supervision égal ou supérieur au sien.
     */
    private void verifierRoleAttribuable(Role role) {
        Role appelant = ContexteUtilisateur.courant().getRole();
        if (appelant == Role.ADMIN) {
            return;
        }
        boolean autorise;
        if (appelant == Role.SERVICE_CENTRAL) {
            autorise = role != Role.ADMIN;
        } else if (appelant == Role.SERVICE_REGIONAL) {
            autorise = role != Role.ADMIN && role != Role.SERVICE_CENTRAL && role != Role.SERVICE_REGIONAL;
        } else {
            autorise = false;
        }
        if (!autorise) {
            throw ExceptionMetier.interdit("Vous ne pouvez pas administrer un compte de rôle " + role + ".");
        }
    }

    /** À l'écriture, le service régional doit avoir une région connue, et ce doit être celle du compte. */
    private void verifierRegion(String regionId) {
        String regionAppelant = ContexteUtilisateur.courant().getRegionId();
        if (regionAppelant == null || !regionAppelant.equals(regionId)) {
            throw ExceptionMetier.interdit("Ce compte n'appartient pas à votre région.");
        }
    }

    private void verifierRattachement(User utilisateur) {
        Role role = utilisateur.getRole();
        if (role != null && role.estStructureSanitaire()
                && (utilisateur.getStructureSanitaireId() == null
                || utilisateur.getStructureSanitaireId().isBlank())) {
            throw ExceptionMetier.invalide(
                    "Un compte de poste ou de centre de santé doit être rattaché à une structure.");
        }
        if (role == Role.PHARMACIEN && (utilisateur.getPharmacieId() == null
                || utilisateur.getPharmacieId().isBlank())) {
            throw ExceptionMetier.invalide("Un compte pharmacien doit être rattaché à une pharmacie.");
        }
        if (role == Role.DISTRICT && (utilisateur.getDistrictId() == null
                || utilisateur.getDistrictId().isBlank())) {
            throw ExceptionMetier.invalide("Un compte district doit être rattaché à un district.");
        }
        if (utilisateur.getStructureSanitaireId() != null && !utilisateur.getStructureSanitaireId().isBlank()) {
            StructureSanitaire structure = depotStructure.findById(utilisateur.getStructureSanitaireId())
                    .orElseThrow(() -> ExceptionMetier.invalide("Structure de rattachement introuvable"));
            // On aligne district et région sur la structure : deux sources divergentes
            // produiraient des consolidations incohérentes.
            utilisateur.setDistrictId(structure.getDistrictId());
            utilisateur.setRegionId(structure.getRegionId());
        }
    }

    private String genererIdentifiant(User demande) {
        if (demande.getUsername() != null && !demande.getUsername().isBlank()) {
            return identifiantDisponible(demande.getUsername());
        }
        if (demande.getStructureSanitaireId() != null) {
            return depotStructure.findById(demande.getStructureSanitaireId())
                    .map(s -> identifiantDisponible(s.getCode()))
                    .orElse(null);
        }
        return null;
    }

    /** Ajoute un suffixe numérique tant que l'identifiant est déjà pris. */
    /**
     * Compte de connexion d'une structure, créé en même temps qu'elle.
     *
     * <p>Le cœur le faisait ; l'extraction l'a perdu, et une structure créée depuis le
     * formulaire n'avait plus aucun compte derrière — l'email et le mot de passe saisis à
     * l'écran étaient enregistrés sur la structure puis oubliés, rendant la connexion
     * impossible. {@code creerComptesStructures} rattrape le retard en masse, mais ne
     * remplace pas la création à l'unité.</p>
     *
     * <p>Sans mot de passe saisi, on retombe sur le mot de passe par défaut, celui du
     * cœur : l'administrateur sait quoi communiquer. Dans tous les cas le compte est
     * marqué à régulariser — son mot de passe est connu d'un tiers.</p>
     *
     * @return le compte et son mot de passe en clair, ou {@code null} si la structure en a déjà un
     */
    @Transactional
    public CompteCree creerCompteStructure(StructureSanitaire structure, String emailSouhaite,
                                           String motDePasseSouhaite) {
        // Un agent BCSU rattaché à la structure n'est pas son compte de connexion.
        if (!comptesDeStructure(structure.getId()).isEmpty()) {
            return null;
        }

        String identifiant = identifiantDisponible(structure.getCode());
        String email = (emailSouhaite == null || emailSouhaite.isBlank())
                ? identifiant.toLowerCase(Locale.ROOT) + "@sencsu.sn"
                : emailSouhaite.trim().toLowerCase(Locale.ROOT);
        if (depotUtilisateur.existsByEmailIgnoreCase(email)) {
            throw ExceptionMetier.conflit("Un compte existe déjà avec l'email « " + email + " »");
        }

        String motDePasseClair = (motDePasseSouhaite == null || motDePasseSouhaite.isBlank())
                ? MOT_DE_PASSE_GENERIQUE : motDePasseSouhaite;

        User compte = depotUtilisateur.save(User.builder()
                .nom(structure.getNom())
                .prenom("")
                .email(email)
                .username(identifiant)
                .password(encodeur.encode(motDePasseClair))
                // Rôle déduit du type, comme à la création en masse.
                .role(structure.typeEffectif() == com.csu.pharmacie.entity.TypeStructure.PS
                        ? Role.POSTE_SANTE : Role.CENTRE_SANTE)
                .structureSanitaireId(structure.getId())
                .districtId(structure.getDistrictId())
                .regionId(structure.getRegionId())
                .actif(true)
                .mustChangePassword(true)
                .build());

        return new CompteCree(compte, motDePasseClair);
    }

    /** Vrai si aucun compte ne porte déjà cet e-mail (vide = non disponible). */
    public boolean emailDisponible(String email) {
        return email != null && !email.isBlank() && !depotUtilisateur.existsByEmailIgnoreCase(email.trim());
    }

    /** Identifiant de connexion du compte d'une structure (après sa création), ou {@code null}. */
    public String identifiantCompteStructure(StructureSanitaire structure) {
        return compteDeStructure(structure).map(ServiceUtilisateur::identifiantDeConnexion).orElse(null);
    }

    // ────────────────────────────── comptes de district ──────────────────────────────

    /** Comptes de connexion d'un district (rôle DISTRICT), du plus ancien au plus récent. */
    public List<User> comptesDeDistrict(String districtId) {
        return depotUtilisateur.findByDistrictId(districtId).stream()
                .filter(u -> u.getRole() == Role.DISTRICT)
                .sorted(Comparator.comparing(User::getCreatedAt,
                                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(User::getId))
                .toList();
    }

    /**
     * Compte de connexion d'un district, créé en même temps que lui — comme pour une structure.
     * Sans lui, l'e-mail saisi sur le district n'était qu'un contact : personne ne pouvait se
     * connecter à l'espace district. Identifiant = code du district, mot de passe par défaut,
     * à changer à la première connexion. L'e-mail saisi sert d'e-mail de connexion s'il est
     * libre, sinon on retombe sur {@code code@sencsu.sn} (la création du district ne doit pas
     * échouer pour un e-mail déjà porté par un autre compte).
     *
     * @return le compte et son mot de passe en clair, ou {@code null} si le district en a déjà un
     */
    @Transactional
    public CompteCree creerCompteDistrict(District district) {
        if (!comptesDeDistrict(district.getId()).isEmpty()) {
            return null;
        }
        String identifiant = identifiantDisponible(district.getCode());
        String parDefaut = identifiant.toLowerCase(Locale.ROOT) + "@sencsu.sn";
        String souhaite = district.getEmail() == null ? "" : district.getEmail().trim().toLowerCase(Locale.ROOT);
        String email = !souhaite.isBlank() && !depotUtilisateur.existsByEmailIgnoreCase(souhaite) ? souhaite : parDefaut;
        if (depotUtilisateur.existsByEmailIgnoreCase(email)) {
            throw ExceptionMetier.conflit("Un compte existe déjà avec l'email « " + email + " »");
        }

        User compte = depotUtilisateur.save(User.builder()
                .nom(district.getNom())
                .prenom("")
                .email(email)
                .username(identifiant)
                .password(encodeur.encode(MOT_DE_PASSE_GENERIQUE))
                .role(Role.DISTRICT)
                .districtId(district.getId())
                .regionId(district.getRegionId())
                .actif(district.isActif())
                .mustChangePassword(true)
                .build());
        return new CompteCree(compte, MOT_DE_PASSE_GENERIQUE);
    }

    /**
     * Accès du district à communiquer : mot de passe remis au défaut sur son compte existant,
     * ou compte créé s'il n'en a pas encore (districts créés ou importés avant la création
     * automatique). Le périmètre est contrôlé par l'appelant, sur le district.
     */
    @Transactional
    public AccesCompte reinitialiserCompteDistrict(District district) {
        String motDePasse = MOT_DE_PASSE_GENERIQUE;
        List<User> comptes = comptesDeDistrict(district.getId());
        Optional<User> existant = comptes.stream()
                .filter(u -> u.getId().equals(district.getResponsableId()))
                .findFirst()
                .or(() -> comptes.stream().findFirst());
        if (existant.isPresent()) {
            User compte = existant.get();
            appliquerMotDePasseTemporaire(compte, motDePasse);
            return new AccesCompte(compte.getId(), identifiantDeConnexion(compte), motDePasse, false);
        }
        CompteCree cree = creerCompteDistrict(district);
        if (cree == null) {
            throw ExceptionMetier.conflit("Le compte de « " + district.getNom() + " » vient d'être créé : réessayez.");
        }
        return new AccesCompte(cree.utilisateur().getId(), identifiantDeConnexion(cree.utilisateur()), motDePasse, true);
    }

    /** Le district et ses comptes vont de pair : le désactiver ferme leur accès, le réactiver le rouvre. */
    @Transactional
    public void basculerComptesDistrict(String districtId, boolean actif) {
        for (User compte : comptesDeDistrict(districtId)) {
            compte.setActif(actif);
            depotUtilisateur.save(compte);
        }
    }

    /**
     * Mot de passe par défaut de tout compte créé ou réinitialisé sans mot de passe saisi,
     * identique à celui du cœur ({@code PharmacieService}). À changer à la première
     * connexion. « password123 » depuis le 03/10/2026 — « csu2026 » et des mots de passe
     * aléatoires auparavant.
     */
    public static final String MOT_DE_PASSE_GENERIQUE = "password123";

    private String identifiantDisponible(String base) {
        String racine = (base == null || base.isBlank() ? "STR" : base)
                .trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String candidat = racine;
        int suffixe = 1;
        while (depotUtilisateur.existsByUsernameIgnoreCase(candidat)) {
            candidat = racine + (++suffixe);
        }
        return candidat;
    }

    /** Compte créé + mot de passe temporaire, renvoyé une seule fois à l'administrateur. */
    public record CompteCree(User utilisateur, String motDePasseTemporaire) {
    }

    /**
     * Accès d'un compte de structure à communiquer, renvoyés une seule fois. {@code compteId}
     * reste côté serveur (rattachement du responsable) ; {@code compteCree} dit si la
     * structure n'avait pas encore de compte.
     */
    public record AccesCompte(String compteId, String identifiant, String motDePasseTemporaire,
                              boolean compteCree) {
    }
}
