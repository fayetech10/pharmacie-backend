package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.PharmacieImportResult;
import com.csu.pharmacie.dto.PharmacieRequest;
import com.csu.pharmacie.entity.*;
import com.csu.pharmacie.exception.ConflictException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.repository.PharmacieRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PharmacieService {

    private final PharmacieRepository pharmacieRepository;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final PasswordEncoder passwordEncoder;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    public List<Pharmacie> getAllPharmacies() {
        User user = getCurrentUser();
        // Le Service Régional ne voit que les pharmacies de sa propre région
        if (user.getRole() == Role.SERVICE_REGIONAL) {
            return pharmacieRepository.findByRegionId(user.getRegionId());
        }
        return pharmacieRepository.findAll();
    }

    public Pharmacie getPharmacieById(String id) {
        return pharmacieRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacie non trouvée"));
    }

    public Pharmacie createPharmacie(PharmacieRequest request) {
        User currentUser = getCurrentUser();
        // Un Service Régional ne peut créer une pharmacie que dans sa propre région
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            request.setRegionId(currentUser.getRegionId());
        }

        return persistPharmacieAvecCompte(
                request.getCode(), request.getNom(), request.getAdresse(),
                request.getTelephone(), request.getEmail(), request.getRegionId(),
                request.getPassword());
    }

    /**
     * Crée une pharmacie ET son compte utilisateur Pharmacien associé.
     * Logique commune à la création unitaire et à l'import en masse.
     */
    private Pharmacie persistPharmacieAvecCompte(String code, String nom, String adresse,
                                                 String telephone, String email,
                                                 String regionId, String password) {
        if (pharmacieRepository.findByCode(code).isPresent()) {
            throw new ConflictException("Code pharmacie déjà utilisé");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Un compte utilisateur existe déjà avec cet email");
        }

        // 1. Créer la pharmacie
        Pharmacie pharmacie = Pharmacie.builder()
                .code(code)
                .nom(nom)
                .adresse(adresse)
                .telephone(telephone)
                .email(email)
                .regionId(regionId)
                .createdAt(LocalDateTime.now())
                .actif(true)
                .build();

        pharmacie = pharmacieRepository.save(pharmacie);

        // 2. Créer l'utilisateur Pharmacien associé.
        // Sans mot de passe fourni, on applique le mot de passe générique : il devra
        // obligatoirement être changé à la première connexion.
        String rawPassword = password;
        boolean motDePasseGenerique = (rawPassword == null || rawPassword.trim().isEmpty());
        if (motDePasseGenerique) {
            rawPassword = MOT_DE_PASSE_GENERIQUE;
        }

        User user = User.builder()
                .nom(nom)
                .prenom("Responsable")
                .email(email)
                .username(genererUsername(pharmacie.getRegionId(), code))
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.PHARMACIEN)
                .pharmacieId(pharmacie.getId())
                .regionId(pharmacie.getRegionId())
                .mustChangePassword(motDePasseGenerique)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .actif(true)
                .build();

        user = userRepository.save(user);

        // 3. Mettre à jour le responsableId de la pharmacie
        pharmacie.setResponsableId(user.getId());
        return pharmacieRepository.save(pharmacie);
    }

    public Pharmacie updatePharmacie(String id, PharmacieRequest request) {
        Pharmacie pharmacie = getPharmacieById(id);
        String oldEmail = pharmacie.getEmail();

        User currentUser = getCurrentUser();
        // Un Service Régional ne peut modifier que les pharmacies de sa propre région,
        // et ne peut pas les transférer vers une autre région.
        if (currentUser.getRole() == Role.SERVICE_REGIONAL) {
            if (!currentUser.getRegionId().equals(pharmacie.getRegionId())) {
                throw new ForbiddenException("Vous ne pouvez modifier que les pharmacies de votre région");
            }
            request.setRegionId(currentUser.getRegionId());
        }

        if (!pharmacie.getCode().equals(request.getCode())) {
            if (pharmacieRepository.findByCode(request.getCode()).isPresent()) {
                throw new ConflictException("Code pharmacie déjà utilisé");
            }
            pharmacie.setCode(request.getCode());
        }

        pharmacie.setNom(request.getNom());
        pharmacie.setAdresse(request.getAdresse());
        pharmacie.setTelephone(request.getTelephone());
        pharmacie.setEmail(request.getEmail());
        pharmacie.setRegionId(request.getRegionId());

        Pharmacie saved = pharmacieRepository.save(pharmacie);

        // Mettre à jour l'utilisateur associé
        userRepository.findByEmail(oldEmail).ifPresent(user -> {
            user.setEmail(saved.getEmail());
            user.setNom(saved.getNom());
            user.setRegionId(saved.getRegionId());
            if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
                user.setPassword(passwordEncoder.encode(request.getPassword()));
            }
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        });

        return saved;
    }

    public void deletePharmacie(String id) {
        Pharmacie pharmacie = getPharmacieById(id);

        User currentUser = getCurrentUser();
        // Un Service Régional ne peut supprimer que les pharmacies de sa propre région
        if (currentUser.getRole() == Role.SERVICE_REGIONAL
                && !currentUser.getRegionId().equals(pharmacie.getRegionId())) {
            throw new ForbiddenException("Vous ne pouvez supprimer que les pharmacies de votre région");
        }

        // Supprimer l'utilisateur associé également
        if (pharmacie.getResponsableId() != null) {
            userRepository.deleteById(pharmacie.getResponsableId());
        } else {
            userRepository.findByEmail(pharmacie.getEmail()).ifPresent(user -> userRepository.deleteById(user.getId()));
        }
        pharmacieRepository.deleteById(id);
    }

    // Colonnes attendues dans le fichier Excel d'import
    private static final String[] COLONNES_IMPORT = {
            "Code", "Nom", "Adresse", "Téléphone", "Email", "Région (code ou nom)", "Mot de passe"
    };

    /**
     * Importe une liste de pharmacies depuis un fichier Excel (.xlsx).
     * Chaque ligne crée une pharmacie ET son compte Pharmacien associé.
     * L'import est tolérant aux erreurs : une ligne en échec n'interrompt pas les autres,
     * son motif est remonté dans le résultat.
     *
     * Colonnes attendues (1re ligne = en-têtes, ignorée) :
     * Code | Nom | Adresse | Téléphone | Email | Région (code ou nom) | Mot de passe
     */
    public PharmacieImportResult importerPharmacies(InputStream is) {
        User currentUser = getCurrentUser();
        PharmacieImportResult result = new PharmacieImportResult();

        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            Sheet sheet = workbook.getSheetAt(0);
            log.info("Début de l'import de pharmacies ({} lignes)", sheet.getLastRowNum());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String code = trim(ExcelUtils.getCellStringValue(row.getCell(0)));
                String nom = trim(ExcelUtils.getCellStringValue(row.getCell(1)));
                String adresse = trim(ExcelUtils.getCellStringValue(row.getCell(2)));
                String telephone = trim(ExcelUtils.getCellStringValue(row.getCell(3)));
                String email = trim(ExcelUtils.getCellStringValue(row.getCell(4)));
                String regionCell = trim(ExcelUtils.getCellStringValue(row.getCell(5)));
                String password = trim(ExcelUtils.getCellStringValue(row.getCell(6)));

                // Ignorer les lignes entièrement vides
                if (code.isEmpty() && nom.isEmpty() && email.isEmpty()) continue;

                int numLigne = i + 1; // numéro de ligne 1-based tel qu'affiché dans Excel
                try {
                    if (code.isEmpty()) throw new IllegalArgumentException("le code est obligatoire");
                    if (nom.isEmpty()) throw new IllegalArgumentException("le nom est obligatoire");
                    if (email.isEmpty()) throw new IllegalArgumentException("l'email est obligatoire");

                    // Un Service Régional importe forcément dans sa propre région
                    String regionId = (currentUser.getRole() == Role.SERVICE_REGIONAL)
                            ? currentUser.getRegionId()
                            : resoudreRegionId(regionCell);

                    persistPharmacieAvecCompte(code, nom, adresse, telephone, email, regionId, password);
                    result.incrementerImportes();
                } catch (Exception e) {
                    String label = nom.isEmpty() ? (code.isEmpty() ? "?" : code) : nom;
                    result.ajouterErreur("Ligne " + numLigne + " (" + label + ") : " + e.getMessage());
                }
            }

            log.info("Import terminé : {} importées, {} échecs", result.getImportes(), result.getEchecs());
            return result;
        } catch (Exception e) {
            log.error("Erreur lors de la lecture du fichier Excel des pharmacies", e);
            throw new RuntimeException("Fichier Excel illisible : " + e.getMessage(), e);
        }
    }

    /** Mot de passe attribué par défaut à la création en masse (à changer à la 1re connexion). */
    public static final String MOT_DE_PASSE_GENERIQUE = "csu2026";

    /**
     * Identifiant de connexion tenant compte de la région : {CODE_REGION}.{CODE_PHARMACIE}
     * (ex. DK.PH001). Un suffixe numérique est ajouté en cas de collision.
     */
    private String genererUsername(String regionId, String codePharmacie) {
        String codeRegion = regionRepository.findById(regionId)
                .map(Region::getCode)
                .filter(c -> c != null && !c.isBlank())
                .orElse("CSU");

        String base = (normaliser(codeRegion) + "." + normaliser(codePharmacie)).toLowerCase();
        String candidat = base;
        int suffixe = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidat)) {
            candidat = base + suffixe++;
        }
        return candidat;
    }

    /** Ne conserve que les caractères alphanumériques (les séparateurs cassent l'identifiant). */
    private String normaliser(String valeur) {
        return valeur == null ? "" : valeur.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * Export Excel des pharmacies avec leurs identifiants de connexion, destiné à être
     * remis aux officines après un import en masse. Le mot de passe n'est jamais stocké
     * en clair : la colonne indique le mot de passe générique tant qu'il n'a pas été changé.
     */
    public byte[] exporterPharmaciesExcel() {
        List<Pharmacie> pharmacies = getAllPharmacies();

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Pharmacies");

            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] colonnes = {
                    "Code", "Nom", "Région", "Email (connexion)", "Username (connexion)",
                    "Mot de passe", "Statut du mot de passe", "Actif"
            };
            Row header = sheet.createRow(0);
            for (int c = 0; c < colonnes.length; c++) {
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(c);
                cell.setCellValue(colonnes[c]);
                cell.setCellStyle(headerStyle);
            }

            Map<String, Region> regionsParId = regionRepository.findAll().stream()
                    .collect(Collectors.toMap(Region::getId, r -> r, (a, b) -> a));

            int ligne = 1;
            for (Pharmacie p : pharmacies) {
                User compte = p.getResponsableId() != null
                        ? userRepository.findById(p.getResponsableId()).orElse(null)
                        : null;
                boolean aChanger = compte != null && Boolean.TRUE.equals(compte.getMustChangePassword());

                Region region = regionsParId.get(p.getRegionId());
                Row row = sheet.createRow(ligne++);
                row.createCell(0).setCellValue(p.getCode() != null ? p.getCode() : "");
                row.createCell(1).setCellValue(p.getNom() != null ? p.getNom() : "");
                row.createCell(2).setCellValue(region != null && region.getNom() != null ? region.getNom() : "");
                row.createCell(3).setCellValue(p.getEmail() != null ? p.getEmail() : "");
                row.createCell(4).setCellValue(compte != null && compte.getUsername() != null ? compte.getUsername() : "");
                row.createCell(5).setCellValue(aChanger ? MOT_DE_PASSE_GENERIQUE : "(personnalisé)");
                row.createCell(6).setCellValue(aChanger ? "À changer à la première connexion" : "Déjà changé");
                row.createCell(7).setCellValue(p.isActif() ? "Oui" : "Non");
            }

            for (int c = 0; c < colonnes.length; c++) {
                sheet.autoSizeColumn(c);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Impossible de générer l'export Excel : " + e.getMessage(), e);
        }
    }

    /** Résout l'id d'une région à partir de son code ou de son nom (insensible à la casse). */
    private String resoudreRegionId(String valeur) {
        if (valeur == null || valeur.isEmpty()) {
            throw new IllegalArgumentException("la région est obligatoire");
        }
        Region region = regionRepository.findByCode(valeur)
                .orElseGet(() -> regionRepository.findAll().stream()
                        .filter(r -> r.getNom() != null && r.getNom().equalsIgnoreCase(valeur))
                        .findFirst()
                        .orElse(null));
        if (region == null) {
            throw new IllegalArgumentException("région introuvable : « " + valeur + " »");
        }
        return region.getId();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Génère un fichier Excel modèle (en-têtes + une ligne d'exemple) pour guider l'import.
     */
    public byte[] genererModeleImport() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Pharmacies");

            // Style d'en-tête : gras sur fond clair
            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int c = 0; c < COLONNES_IMPORT.length; c++) {
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(c);
                cell.setCellValue(COLONNES_IMPORT[c]);
                cell.setCellStyle(headerStyle);
            }

            // Ligne d'exemple
            Row exemple = sheet.createRow(1);
            String[] valeursExemple = {
                    "PH001", "Pharmacie Guigon", "Plateau, Dakar",
                    "33 800 00 00", "contact@guigon.sn", "Dakar", "password123"
            };
            for (int c = 0; c < valeursExemple.length; c++) {
                exemple.createCell(c).setCellValue(valeursExemple[c]);
            }

            for (int c = 0; c < COLONNES_IMPORT.length; c++) {
                sheet.autoSizeColumn(c);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Impossible de générer le modèle Excel : " + e.getMessage(), e);
        }
    }
}
