package com.csu.pharmacie.identite.dto;

import com.csu.pharmacie.entity.StructureSanitaire;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;

/**
 * Corps du formulaire « structure sanitaire ».
 *
 * <p>L'écran envoie les champs de la structure <em>et</em> un mot de passe pour le compte
 * de connexion. Or {@code StructureSanitaire} n'a pas de champ mot de passe — c'est une
 * structure, pas un utilisateur — et Jackson jetait donc cette valeur en silence : le
 * compte était créé avec le mot de passe générique alors que l'administrateur en avait
 * saisi un.</p>
 *
 * <p>{@code @JsonUnwrapped} garde le JSON à plat : le contrat de l'API ne change pas, le
 * frontend n'a rien à modifier.</p>
 */
@Data
public class RequeteStructure {

    @JsonUnwrapped
    private StructureSanitaire structure = new StructureSanitaire();

    /** Facultatif : sans lui, le compte reçoit le mot de passe générique de premier accès. */
    private String password;
}
