package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.PharmacieImportResult;
import com.csu.pharmacie.dto.StructureSanitaireRequest;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.StructureSanitaire;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.BusinessException;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.StructureSanitaireRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StructureSanitaireService {

    private final StructureSanitaireRepository structureRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NumeroSequenceService numeroSequenceService;
    private final com.csu.pharmacie.repository.RegionRepository regionRepository;

    /**
     * Identifiant de connexion tenant compte de la région : {CODE_REGION}.{CODE_STRUCTURE}
     * (ex. DK.DKCS01). Un suffixe numérique est ajouté en cas de collision.
     */
    private String genererUsername(String regionId, String codeStructure) {
        String codeRegion = regionId == null ? null : regionRepository.findById(regionId)
                .map(com.csu.pharmacie.entity.Region::getCode)
                .filter(c -> c != null && !c.isBlank())
                .orElse(null);

        String base = ((codeRegion == null ? "CSU" : codeRegion) + "." + (codeStructure == null ? "" : codeStructure))
                .replaceAll("[^A-Za-z0-9.]", "").toLowerCase(Locale.ROOT);
        String candidat = base;
        int suffixe = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidat)) {
            candidat = base + suffixe++;
        }
        return candidat;
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<StructureSanitaire> getAll() {
        User user = getCurrentUser();
        // Le Service Régional ne voit que les structures de sa propre région.
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            return structureRepository.findByRegionId(user.getRegionId());
        }
        return structureRepository.findAll();
    }

    /** Structures rattachées à un agent BCSU précis (par son userId). */
    public List<StructureSanitaire> getByBcsuId(String bcsuId) {
        return structureRepository.findByBcsuId(bcsuId);
    }

    /** Postes de santé polarisés (rattachés) par une structure sanitaire. */
    public List<StructureSanitaire> getPostesRattaches(String structureId) {
        return structureRepository.findByStructureRattachementId(structureId);
    }

    /**
     * Type effectif d'une structure : champ persistant s'il existe, sinon déduit
     * du code auto-généré ({REGION}{TYPE}{NN}, ex: DKPS01 → PS) pour les données existantes.
     */
    public static String typeEffectif(StructureSanitaire s) {
        if (s.getTypeStructure() != null && !s.getTypeStructure().isBlank()) {
            return s.getTypeStructure().trim().toUpperCase(Locale.ROOT);
        }
        String code = s.getCode() == null ? "" : s.getCode().toUpperCase(Locale.ROOT);
        if (code.matches("^[A-Z]{2}PS\\d+$")) return "PS";
        if (code.matches("^[A-Z]{2}EPS\\d+$")) return "EPS";
        if (code.matches("^[A-Z]{2}CS\\d+$")) return "CS";
        return "CS";
    }

    public StructureSanitaire getById(String id) {
        return structureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Structure sanitaire non trouvée"));
    }

    @Transactional
    public StructureSanitaire create(StructureSanitaireRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            request.setRegionId(currentUser.getRegionId());
        }

        // Code généré automatiquement ({REGION}{TYPE}{NN}, ex: DKCS01) comme les numéros LG/BC.
        // Un code saisi manuellement (reprise de données) reste accepté s'il est libre.
        String code;
        if (request.getCode() == null || request.getCode().isBlank()) {
            code = genererCodeUnique(request.getRegionId(), request.getTypeStructure());
        } else {
            code = request.getCode().trim();
            if (structureRepository.findByCode(code).isPresent()) {
                throw new ConflictException("Code structure déjà utilisé");
            }
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Un compte utilisateur existe déjà avec cet email");
        }

        String typeStructure = (request.getTypeStructure() == null || request.getTypeStructure().isBlank())
                ? "CS" : request.getTypeStructure().trim().toUpperCase(Locale.ROOT);
        String rattachementId = validerRattachement(typeStructure, request.getStructureRattachementId(), request.getRegionId());

        StructureSanitaire structure = StructureSanitaire.builder()
                .code(code)
                .nom(request.getNom())
                .adresse(request.getAdresse())
                .telephone(request.getTelephone())
                .email(request.getEmail())
                .regionId(request.getRegionId())
                .typeStructure(typeStructure)
                .structureRattachementId(rattachementId)
                .bcsuId(request.getBcsuId())
                .actif(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        structure = structureRepository.save(structure);

        // Crée le compte agent (rôle STRUCTURE_SANITAIRE) si un email est fourni.
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            // Sans mot de passe fourni : mot de passe générique à changer à la 1re connexion.
            boolean motDePasseGenerique = (request.getPassword() == null || request.getPassword().isBlank());
            String rawPassword = motDePasseGenerique
                    ? PharmacieService.MOT_DE_PASSE_GENERIQUE : request.getPassword();
            User agent = User.builder()
                    .nom(request.getNom())
                    .prenom("Agent")
                    .email(request.getEmail())
                    .username(genererUsername(request.getRegionId(), structure.getCode()))
                    .password(passwordEncoder.encode(rawPassword))
                    .role(Role.STRUCTURE_SANITAIRE)
                    .regionId(request.getRegionId())
                    .structureSanitaireId(structure.getId())
                    .mustChangePassword(motDePasseGenerique)
                    .actif(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            agent = userRepository.save(agent);
            structure.setResponsableId(agent.getId());
            structure = structureRepository.save(structure);
        }
        return structure;
    }

    @Transactional
    public StructureSanitaire update(String id, StructureSanitaireRequest request) {
        StructureSanitaire structure = getById(id);
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            if (!currentUser.getRegionId().equals(structure.getRegionId())) {
                throw new ForbiddenException("Vous ne pouvez modifier que les structures de votre région");
            }
            request.setRegionId(currentUser.getRegionId());
        }

        // Le code auto-généré est stable : il n'est remplacé que si un nouveau code est saisi.
        String nouveauCode = (request.getCode() == null || request.getCode().isBlank())
                ? structure.getCode() : request.getCode().trim();
        if (!structure.getCode().equals(nouveauCode)
                && structureRepository.findByCode(nouveauCode).isPresent()) {
            throw new ConflictException("Code structure déjà utilisé");
        }

        String oldEmail = structure.getEmail();
        structure.setCode(nouveauCode);
        structure.setNom(request.getNom());
        structure.setAdresse(request.getAdresse());
        structure.setTelephone(request.getTelephone());
        structure.setEmail(request.getEmail());
        structure.setRegionId(request.getRegionId());
        structure.setBcsuId(request.getBcsuId());
        if (request.getTypeStructure() != null && !request.getTypeStructure().isBlank()) {
            structure.setTypeStructure(request.getTypeStructure().trim().toUpperCase(Locale.ROOT));
        }
        structure.setStructureRattachementId(validerRattachement(
                typeEffectif(structure), request.getStructureRattachementId(), structure.getRegionId()));
        structure.setUpdatedAt(LocalDateTime.now());
        StructureSanitaire saved = structureRepository.save(structure);

        // Répercute sur le compte agent associé.
        if (oldEmail != null && !oldEmail.isBlank()) {
            userRepository.findByEmail(oldEmail).ifPresent(agent -> {
                agent.setEmail(saved.getEmail());
                agent.setNom(saved.getNom());
                agent.setRegionId(saved.getRegionId());
                if (request.getPassword() != null && !request.getPassword().isBlank()) {
                    agent.setPassword(passwordEncoder.encode(request.getPassword()));
                }
                agent.setUpdatedAt(LocalDateTime.now());
                userRepository.save(agent);
            });
        }
        return saved;
    }

    /** Code auto-généré ({REGION}{TYPE}{NN}) ; la séquence avance à chaque essai, les collisions sont improbables. */
    private String genererCodeUnique(String regionId, String typeStructure) {
        for (int i = 0; i < 5; i++) {
            String candidat = numeroSequenceService.prochainCodeStructure(regionId, typeStructure);
            if (structureRepository.findByCode(candidat).isEmpty()) {
                return candidat;
            }
        }
        throw new ConflictException("Impossible de générer un code structure unique, veuillez réessayer");
    }

    /**
     * Vérifie la cohérence du rattachement d'un poste de santé : la structure de
     * rattachement doit exister, ne pas être elle-même un poste, et appartenir à la même région.
     * Renvoie l'id validé (null si non applicable).
     */
    private String validerRattachement(String typeStructure, String rattachementId, String regionId) {
        if (rattachementId == null || rattachementId.isBlank()) {
            if ("PS".equals(typeStructure)) {
                throw new BusinessException("Un poste de santé doit être rattaché à une structure sanitaire (CS ou EPS)");
            }
            return null;
        }
        StructureSanitaire parent = structureRepository.findById(rattachementId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure de rattachement non trouvée"));
        if ("PS".equals(typeEffectif(parent))) {
            throw new BusinessException("Un poste de santé ne peut pas être rattaché à un autre poste de santé");
        }
        if (regionId != null && parent.getRegionId() != null && !regionId.equals(parent.getRegionId())) {
            throw new BusinessException("La structure de rattachement doit appartenir à la même région");
        }
        return parent.getId();
    }

    // Colonnes du fichier Excel d'import des structures sanitaires
    private static final String[] COLONNES_IMPORT_POSTES = {
            "Type (EPS/CS/PS)", "Nom", "Adresse", "Téléphone", "Email (compte de connexion)",
            "Mot de passe", "Structure de rattachement (code ou nom) — PS uniquement",
            "Région (code ou nom)"
    };

    /**
     * Importe des structures sanitaires depuis un fichier Excel (.xlsx) : hôpitaux (EPS),
     * centres de santé (CS) et postes de santé (PS). Le rattachement n'est exigé que pour
     * les PS, qui doivent dépendre d'un CS ou d'un EPS.
     * Une ligne en échec n'interrompt pas les autres (erreur remontée dans le résultat).
     */
    @Transactional
    public PharmacieImportResult importerPostes(InputStream is) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.SERVICE_REGIONAL && currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Seul le service régional peut importer des postes de santé");
        }
        PharmacieImportResult result = new PharmacieImportResult();

        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            Sheet sheet = workbook.getSheetAt(0);
            log.info("Début de l'import de postes de santé ({} lignes)", sheet.getLastRowNum());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String type = trim(ExcelUtils.getCellStringValue(row.getCell(0))).toUpperCase();
                String nom = trim(ExcelUtils.getCellStringValue(row.getCell(1)));
                String adresse = trim(ExcelUtils.getCellStringValue(row.getCell(2)));
                String telephone = trim(ExcelUtils.getCellStringValue(row.getCell(3)));
                String email = trim(ExcelUtils.getCellStringValue(row.getCell(4)));
                String password = trim(ExcelUtils.getCellStringValue(row.getCell(5)));
                String rattachementCell = trim(ExcelUtils.getCellStringValue(row.getCell(6)));
                String regionCell = trim(ExcelUtils.getCellStringValue(row.getCell(7)));

                if (nom.isEmpty() && email.isEmpty() && rattachementCell.isEmpty()) continue;

                int numLigne = i + 1;
                try {
                    if (nom.isEmpty()) throw new IllegalArgumentException("le nom de la structure est obligatoire");
                    if (type.isEmpty()) type = "PS"; // compatibilité avec les anciens fichiers « postes »
                    if (!List.of("EPS", "CS", "PS").contains(type)) {
                        throw new IllegalArgumentException("type invalide « " + type + " » (attendu EPS, CS ou PS)");
                    }

                    StructureSanitaireRequest req = new StructureSanitaireRequest();
                    req.setNom(nom);
                    req.setAdresse(adresse.isEmpty() ? null : adresse);
                    req.setTelephone(telephone.isEmpty() ? null : telephone);
                    req.setEmail(email.isEmpty() ? null : email);
                    req.setPassword(password.isEmpty() ? null : password);
                    req.setTypeStructure(type);

                    // Seul un poste de santé doit être rattaché à un CS / EPS.
                    String regionId;
                    if ("PS".equals(type)) {
                        if (rattachementCell.isEmpty()) {
                            throw new IllegalArgumentException("la structure de rattachement est obligatoire pour un poste de santé");
                        }
                        StructureSanitaire parent = resoudreStructure(rattachementCell, currentUser);
                        req.setStructureRattachementId(parent.getId());
                        regionId = currentUser.getRole() == Role.SERVICE_REGIONAL
                                ? currentUser.getRegionId() : parent.getRegionId();
                    } else {
                        // Un SR importe dans sa région ; un ADMIN doit préciser la région.
                        regionId = currentUser.getRole() == Role.SERVICE_REGIONAL
                                ? currentUser.getRegionId() : resoudreRegionId(regionCell);
                    }
                    req.setRegionId(regionId);

                    create(req);
                    result.incrementerImportes();
                } catch (Exception e) {
                    result.ajouterErreur("Ligne " + numLigne + " (" + (nom.isEmpty() ? "?" : nom) + ") : " + e.getMessage());
                }
            }

            log.info("Import des postes terminé : {} importés, {} échecs", result.getImportes(), result.getEchecs());
            return result;
        } catch (BusinessException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erreur lors de la lecture du fichier Excel des postes de santé", e);
            throw new BusinessException("Fichier Excel illisible : " + e.getMessage());
        }
    }

    /** Résout une structure sanitaire (CS/EPS) par code ou nom, dans la région de l'utilisateur pour un SR. */
    private StructureSanitaire resoudreStructure(String valeur, User currentUser) {
        List<StructureSanitaire> candidates = currentUser.getRole() == Role.SERVICE_REGIONAL
                ? structureRepository.findByRegionId(currentUser.getRegionId())
                : structureRepository.findAll();
        return candidates.stream()
                .filter(s -> !"PS".equals(typeEffectif(s)))
                .filter(s -> (s.getCode() != null && s.getCode().equalsIgnoreCase(valeur))
                        || (s.getNom() != null && s.getNom().equalsIgnoreCase(valeur)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("structure de rattachement introuvable : « " + valeur + " »"));
    }

    /** Résout l'id d'une région à partir de son code ou de son nom (insensible à la casse). */
    private String resoudreRegionId(String valeur) {
        if (valeur == null || valeur.isEmpty()) {
            throw new IllegalArgumentException("la région est obligatoire pour un EPS ou un centre de santé");
        }
        return regionRepository.findByCode(valeur)
                .or(() -> regionRepository.findAll().stream()
                        .filter(r -> r.getNom() != null && r.getNom().equalsIgnoreCase(valeur))
                        .findFirst())
                .map(com.csu.pharmacie.entity.Region::getId)
                .orElseThrow(() -> new IllegalArgumentException("région introuvable : « " + valeur + " »"));
    }

    /** Fichier Excel modèle (en-têtes + exemples EPS/CS/PS) pour l'import des structures. */
    public byte[] genererModeleImportPostes() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Structures sanitaires");
            Row header = sheet.createRow(0);
            for (int i = 0; i < COLONNES_IMPORT_POSTES.length; i++) {
                header.createCell(i).setCellValue(COLONNES_IMPORT_POSTES[i]);
            }

            // Un exemple par type : le rattachement ne concerne que le poste de santé.
            String[][] exemples = {
                    {"EPS", "Hôpital Principal", "Avenue Nelson Mandela", "33 839 50 50", "hopital.principal@sencsu.sn", "", "", "Dakar"},
                    {"CS", "Centre de Santé Gaspard Camara", "Rue 15 Angle 22", "33 821 00 00", "cs.gaspard@sencsu.sn", "", "", "Dakar"},
                    {"PS", "Poste de Santé Médina", "Quartier Médina", "77 000 00 00", "poste.medina@sencsu.sn", "", "DKCS01", "Dakar"}
            };
            for (int l = 0; l < exemples.length; l++) {
                Row ligne = sheet.createRow(l + 1);
                for (int c = 0; c < exemples[l].length; c++) {
                    ligne.createCell(c).setCellValue(exemples[l][c]);
                }
            }

            for (int i = 0; i < COLONNES_IMPORT_POSTES.length; i++) {
                sheet.setColumnWidth(i, 28 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("Impossible de générer le modèle d'import : " + e.getMessage());
        }
    }

    /**
     * Export Excel des structures sanitaires avec leurs identifiants de connexion,
     * à remettre aux structures après un import en masse.
     */
    public byte[] exporterStructuresExcel() {
        List<StructureSanitaire> structures = getAll();
        Map<String, StructureSanitaire> parId = structures.stream()
                .collect(java.util.stream.Collectors.toMap(StructureSanitaire::getId, s -> s, (a, b) -> a));

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Structures sanitaires");

            String[] colonnes = {
                    "Code", "Nom", "Type", "Rattachement", "Email (connexion)",
                    "Username (connexion)", "Mot de passe", "Statut du mot de passe", "Actif"
            };
            Row header = sheet.createRow(0);
            for (int c = 0; c < colonnes.length; c++) {
                header.createCell(c).setCellValue(colonnes[c]);
            }

            int ligne = 1;
            for (StructureSanitaire s : structures) {
                User compte = s.getResponsableId() != null
                        ? userRepository.findById(s.getResponsableId()).orElse(null)
                        : null;
                boolean aChanger = compte != null && Boolean.TRUE.equals(compte.getMustChangePassword());
                StructureSanitaire parent = s.getStructureRattachementId() != null
                        ? parId.get(s.getStructureRattachementId()) : null;

                Row row = sheet.createRow(ligne++);
                row.createCell(0).setCellValue(s.getCode() != null ? s.getCode() : "");
                row.createCell(1).setCellValue(s.getNom() != null ? s.getNom() : "");
                row.createCell(2).setCellValue(typeEffectif(s));
                row.createCell(3).setCellValue(parent != null && parent.getNom() != null ? parent.getNom() : "");
                row.createCell(4).setCellValue(s.getEmail() != null ? s.getEmail() : "");
                row.createCell(5).setCellValue(compte != null && compte.getUsername() != null ? compte.getUsername() : "");
                row.createCell(6).setCellValue(aChanger ? PharmacieService.MOT_DE_PASSE_GENERIQUE : "(personnalisé)");
                row.createCell(7).setCellValue(aChanger ? "À changer à la première connexion" : "Déjà changé");
                row.createCell(8).setCellValue(s.isActif() ? "Oui" : "Non");
            }

            for (int c = 0; c < colonnes.length; c++) {
                sheet.setColumnWidth(c, 28 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("Impossible de générer l'export Excel : " + e.getMessage());
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Transactional
    public void delete(String id) {
        StructureSanitaire structure = getById(id);
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.SERVICE_REGIONAL
                && !currentUser.getRegionId().equals(structure.getRegionId())) {
            throw new ForbiddenException("Vous ne pouvez supprimer que les structures de votre région");
        }
        if (structure.getResponsableId() != null) {
            userRepository.deleteById(structure.getResponsableId());
        } else if (structure.getEmail() != null) {
            userRepository.findByEmail(structure.getEmail())
                    .ifPresent(u -> userRepository.deleteById(u.getId()));
        }
        structureRepository.deleteById(id);
    }
}
