package com.csu.pharmacie.service;

import com.csu.pharmacie.dto.BaseImportResult;
import com.csu.pharmacie.entity.BaseImmatricule;
import com.csu.pharmacie.entity.BaseTraite;
import com.csu.pharmacie.entity.Region;
import com.csu.pharmacie.entity.Role;
import com.csu.pharmacie.entity.User;
import com.csu.pharmacie.exception.ForbiddenException;
import com.csu.pharmacie.exception.ResourceNotFoundException;
import com.csu.pharmacie.repository.BaseImmatriculeRepository;
import com.csu.pharmacie.repository.BaseTraiteRepository;
import com.csu.pharmacie.repository.RegionRepository;
import com.csu.pharmacie.repository.UserRepository;
import com.csu.pharmacie.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Import et consultation des « bases » (traité / immatriculé & imprimé) réservées
 * au service régional de Thiès. Le parsing Excel suit le même pattern tolérant que
 * {@link PharmacieService} (Apache POI + {@link ExcelUtils}, erreurs par ligne).
 * L'import est « ajout sans doublons » (clé naturelle par base).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseImportService {

    /** Nom de région (normalisé, sans accent ni casse) autorisé pour cette fonctionnalité. */
    private static final String REGION_AUTORISEE = "thies";

    private static final String[] COLONNES_TRAITE = {
            "Region", "Departement", "Commune", "Nom", "Prenom", "Date_Naissance",
            "Lieu_Naissance", "Sexe", "Telephone", "Adresse", "CNI/Extrait", "Assureur",
            "Regime", "Type_Adhesion", "Type_Beneficiaire", "Type_Cotisation",
            "Date_Cotisation", "Photo", "Index"
    };

    private static final String[] COLONNES_IMMATRICULE = {
            "ID", "Date Enregistrement", "Code Immatriculation", "Nom", "Prénom",
            "Date Naissance", "Sexe", "Téléphone", "Adresse", "Régime", "Assureur",
            "Type Bénéficiaire", "Date Cotisation", "Date Fin Cotisation", "QR Code URL",
            "Region", "Departement", "Commune", "Groupe", "Type_Adhesion",
            "Type_Cotisation", "CNI", "Photo"
    };

    private final BaseTraiteRepository baseTraiteRepository;
    private final BaseImmatriculeRepository baseImmatriculeRepository;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;

    // ----- Sécurité métier : service régional de Thiès uniquement -----

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    /**
     * Vérifie que l'utilisateur courant est un Service Régional rattaché à la région
     * de Thiès, et renvoie son identifiant de région. Sinon, lève une exception interdite.
     */
    private String getRegionThiesOrForbidden() {
        User user = getCurrentUser();
        if (user.getRole() != Role.SERVICE_REGIONAL || user.getRegionId() == null) {
            throw new ForbiddenException("Fonctionnalité réservée au service régional de Thiès");
        }
        Region region = regionRepository.findById(user.getRegionId())
                .orElseThrow(() -> new ForbiddenException("Région de l'utilisateur introuvable"));
        if (!REGION_AUTORISEE.equals(normaliser(region.getNom()))) {
            throw new ForbiddenException("Fonctionnalité réservée au service régional de Thiès");
        }
        return user.getRegionId();
    }

    // ----- Consultation -----

    public List<BaseTraite> getBaseTraite() {
        String regionId = getRegionThiesOrForbidden();
        return baseTraiteRepository.findByRegionIdOrderByImportedAtDesc(regionId);
    }

    public List<BaseImmatricule> getBaseImmatricule() {
        String regionId = getRegionThiesOrForbidden();
        return baseImmatriculeRepository.findByRegionIdOrderByImportedAtDesc(regionId);
    }

    // ----- Import « base traité » -----

    public BaseImportResult importerBaseTraite(InputStream is) {
        String regionId = getRegionThiesOrForbidden();
        String importedBy = getCurrentUser().getEmail();
        BaseImportResult result = new BaseImportResult();
        LocalDateTime now = LocalDateTime.now();

        // Clés (CNI/Extrait + Nom) déjà vues dans CE fichier, pour ignorer les doublons internes.
        Set<String> clesVues = new HashSet<>();
        List<BaseTraite> aSauvegarder = new ArrayList<>();

        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            Sheet sheet = workbook.getSheetAt(0);
            log.info("Import base traité Thiès ({} lignes)", sheet.getLastRowNum());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                BaseTraite e = BaseTraite.builder()
                        .region(cell(row, 0))
                        .departement(cell(row, 1))
                        .commune(cell(row, 2))
                        .nom(cell(row, 3))
                        .prenom(cell(row, 4))
                        .dateNaissance(cell(row, 5))
                        .lieuNaissance(cell(row, 6))
                        .sexe(cell(row, 7))
                        .telephone(cell(row, 8))
                        .adresse(cell(row, 9))
                        .cniExtrait(cell(row, 10))
                        .assureur(cell(row, 11))
                        .regime(cell(row, 12))
                        .typeAdhesion(cell(row, 13))
                        .typeBeneficiaire(cell(row, 14))
                        .typeCotisation(cell(row, 15))
                        .dateCotisation(cell(row, 16))
                        .photo(cell(row, 17))
                        .indexExcel(cell(row, 18))
                        .regionId(regionId)
                        .importedAt(now)
                        .importedBy(importedBy)
                        .build();

                // Ignorer les lignes entièrement vides
                if (estVide(e.getNom()) && estVide(e.getPrenom()) && estVide(e.getCniExtrait())) continue;

                int numLigne = i + 1; // numéro 1-based tel qu'affiché dans Excel
                try {
                    if (estVide(e.getNom())) throw new IllegalArgumentException("le nom est obligatoire");

                    // Déduplication par clé naturelle (CNI/Extrait + Nom), quand la CNI est présente.
                    if (!estVide(e.getCniExtrait())) {
                        String cle = (e.getCniExtrait() + "|" + e.getNom()).toLowerCase();
                        if (clesVues.contains(cle)
                                || baseTraiteRepository.existsByRegionIdAndCniExtraitAndNom(regionId, e.getCniExtrait(), e.getNom())) {
                            result.incrementerDoublons();
                            continue;
                        }
                        clesVues.add(cle);
                    }

                    aSauvegarder.add(e);
                    result.incrementerImportes();
                } catch (Exception ex) {
                    result.ajouterErreur("Ligne " + numLigne + " (" + libelle(e.getNom(), e.getCniExtrait()) + ") : " + ex.getMessage());
                }
            }

            baseTraiteRepository.saveAll(aSauvegarder);
            log.info("Import base traité terminé : {} importées, {} doublons, {} échecs",
                    result.getImportes(), result.getDoublons(), result.getEchecs());
            return result;
        } catch (Exception e) {
            log.error("Erreur lecture Excel base traité", e);
            throw new RuntimeException("Fichier Excel illisible : " + e.getMessage(), e);
        }
    }

    // ----- Import « base immatriculé & imprimé » -----

    public BaseImportResult importerBaseImmatricule(InputStream is) {
        String regionId = getRegionThiesOrForbidden();
        String importedBy = getCurrentUser().getEmail();
        BaseImportResult result = new BaseImportResult();
        LocalDateTime now = LocalDateTime.now();

        Set<String> clesVues = new HashSet<>();
        List<BaseImmatricule> aSauvegarder = new ArrayList<>();

        try (BufferedInputStream bis = new BufferedInputStream(is);
             Workbook workbook = new XSSFWorkbook(bis)) {

            Sheet sheet = workbook.getSheetAt(0);
            log.info("Import base immatriculé Thiès ({} lignes)", sheet.getLastRowNum());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                BaseImmatricule e = BaseImmatricule.builder()
                        .externalId(cell(row, 0))
                        .dateEnregistrement(cell(row, 1))
                        .codeImmatriculation(cell(row, 2))
                        .nom(cell(row, 3))
                        .prenom(cell(row, 4))
                        .dateNaissance(cell(row, 5))
                        .sexe(cell(row, 6))
                        .telephone(cell(row, 7))
                        .adresse(cell(row, 8))
                        .regime(cell(row, 9))
                        .assureur(cell(row, 10))
                        .typeBeneficiaire(cell(row, 11))
                        .dateCotisation(cell(row, 12))
                        .dateFinCotisation(cell(row, 13))
                        .qrCodeUrl(cell(row, 14))
                        .region(cell(row, 15))
                        .departement(cell(row, 16))
                        .commune(cell(row, 17))
                        .groupe(cell(row, 18))
                        .typeAdhesion(cell(row, 19))
                        .typeCotisation(cell(row, 20))
                        .cni(cell(row, 21))
                        .photo(cell(row, 22))
                        .regionId(regionId)
                        .importedAt(now)
                        .importedBy(importedBy)
                        .build();

                if (estVide(e.getCodeImmatriculation()) && estVide(e.getNom()) && estVide(e.getCni())) continue;

                int numLigne = i + 1;
                try {
                    if (estVide(e.getNom()) && estVide(e.getCodeImmatriculation())) {
                        throw new IllegalArgumentException("le nom ou le code d'immatriculation est obligatoire");
                    }

                    // Déduplication par Code Immatriculation, quand il est présent.
                    if (!estVide(e.getCodeImmatriculation())) {
                        String cle = e.getCodeImmatriculation().toLowerCase();
                        if (clesVues.contains(cle)
                                || baseImmatriculeRepository.existsByRegionIdAndCodeImmatriculation(regionId, e.getCodeImmatriculation())) {
                            result.incrementerDoublons();
                            continue;
                        }
                        clesVues.add(cle);
                    }

                    aSauvegarder.add(e);
                    result.incrementerImportes();
                } catch (Exception ex) {
                    result.ajouterErreur("Ligne " + numLigne + " (" + libelle(e.getNom(), e.getCodeImmatriculation()) + ") : " + ex.getMessage());
                }
            }

            baseImmatriculeRepository.saveAll(aSauvegarder);
            log.info("Import base immatriculé terminé : {} importées, {} doublons, {} échecs",
                    result.getImportes(), result.getDoublons(), result.getEchecs());
            return result;
        } catch (Exception e) {
            log.error("Erreur lecture Excel base immatriculé", e);
            throw new RuntimeException("Fichier Excel illisible : " + e.getMessage(), e);
        }
    }

    // ----- Modèles Excel -----

    public byte[] genererModeleTraite() {
        getRegionThiesOrForbidden();
        return genererModele("Base traité", COLONNES_TRAITE);
    }

    public byte[] genererModeleImmatricule() {
        getRegionThiesOrForbidden();
        return genererModele("Base immatriculé", COLONNES_IMMATRICULE);
    }

    private byte[] genererModele(String nomFeuille, String[] colonnes) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(nomFeuille);

            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int c = 0; c < colonnes.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(colonnes[c]);
                cell.setCellStyle(headerStyle);
                sheet.autoSizeColumn(c);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Impossible de générer le modèle Excel : " + e.getMessage(), e);
        }
    }

    // ----- Utilitaires -----

    private String cell(Row row, int index) {
        return trim(ExcelUtils.getCellStringValue(row.getCell(index)));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean estVide(String value) {
        return value == null || value.isEmpty();
    }

    private String libelle(String nom, String secours) {
        if (!estVide(nom)) return nom;
        return estVide(secours) ? "?" : secours;
    }

    /** Normalise une chaîne pour comparaison : sans accents, sans espaces, en minuscules. */
    private static String normaliser(String s) {
        if (s == null) return "";
        String sansAccent = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccent.trim().toLowerCase();
    }
}
