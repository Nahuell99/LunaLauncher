# Release automatiche su Google Play

Puoi automatizzare gli aggiornamenti **già nei test aperti**. Non serve una
release in produzione. L’app deve esistere in Play Console, avere già un primo
APK/AAB caricato manualmente e aver completato le dichiarazioni richieste da
Google. L’API non crea l’app e non accetta al tuo posto gli accordi di pubblicazione.

## 1. Crea l’accesso Google

1. Apri [Google Cloud Console](https://console.cloud.google.com/) e seleziona o crea
   un progetto per BetterLauncher.
2. Abilita la [Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com).
3. In **IAM e amministrazione → Account di servizio**, crea un account, per esempio
   `betterlauncher-release`. Non serve assegnargli il ruolo Owner del progetto.
4. Copia l’indirizzo email dell’account di servizio. In **Google Play Console →
   Utenti e autorizzazioni → Invita nuovi utenti**, inserisci quell’email e limita
   l’accesso a BetterLauncher. Assegna **Visualizzare informazioni sull’app
   (sola lettura)** e **Rilasciare app nei canali di test**. Non occorrono accesso
   finanziario, gestione degli utenti o pubblicazione in produzione.
5. In Google Cloud, apri l’account di servizio → **Chiavi → Aggiungi chiave → Crea
   nuova chiave → JSON** e scarica il file. Se un criterio dell’organizzazione
   impedisce la creazione di chiavi, si può configurare Workload Identity
   Federation anziché disattivare quel criterio.

Non è più necessario collegare l’account sviluppatore a un progetto Cloud.
La chiave JSON contiene una credenziale privata: va nel secret, mai nel repository
o in un messaggio. Conservane una copia privata e revocala se viene esposta.

## 2. Salva il secret su GitHub

Nel repository BetterLauncher apri **Settings → Secrets and variables → Actions →
Secrets → New repository secret**:

| Secret | Valore |
| --- | --- |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Il contenuto completo del file JSON scaricato |

Non serve convertirlo in Base64. Con GitHub CLI puoi impostarlo senza stamparlo:

```sh
gh secret set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON --repo pierspad/BetterLauncher < /percorso/privato/account-di-servizio.json
```

Il workflow di release firma già l’AAB usando `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Quella chiave deve essere la
**chiave di caricamento** accettata da Google Play: controllala in Play Console →
**Integrità dell’app → Firma dell’app → Certificato della chiave di caricamento**.
La chiave con cui Google firma gli APK distribuiti può essere diversa.

## 3. Prima prova manuale

Dopo aver revisionato e pushato questi file:

1. Vai su **Actions → Publish existing release to Google Play → Run workflow**.
2. Inserisci un tag stabile presente su GitHub, per esempio `v1.4.4`.
3. Usa `beta` per il canale di **test aperto** e `draft` per salvare una bozza.
4. Controlla l’esito e la bozza in Play Console. Un’app ancora nello stato bozza
   deve usare `draft` finché i passaggi iniziali nella Console non sono completati.
5. Per distribuire ai tester, usa `completed` per una nuova release/versionCode,
   oppure completa la bozza dalla Console. Non ricaricare un versionCode già usato.

Il workflow scarica l’AAB **firmato già pubblicato nella release GitHub**, senza
ricompilarlo o cambiare versione. Il nome API del canale aperto è `beta`; eventuali
canali chiusi hanno nomi configurati nella Console. Lo script controlla che il
canale esista prima dell’upload e rifiuta `production`.

## 4. Attiva gli aggiornamenti automatici

In **Settings → Secrets and variables → Actions → Variables** aggiungi:

| Variabile | Valore |
| --- | --- |
| `GOOGLE_PLAY_AUTO_PUBLISH` | `true` |
| `GOOGLE_PLAY_TRACK` | `beta` |
| `GOOGLE_PLAY_RELEASE_STATUS` | `completed` per i tester, oppure `draft` per preparare bozze |

Finché `GOOGLE_PLAY_AUTO_PUBLISH` manca o è diversa da `true`, il caricamento
automatico resta disattivato. Senza una scelta di stato esplicita si usa `draft`.

Dopo una nuova release stabile creata da semantic-release, il job `publish-play`
carica il suo AAB nel canale selezionato. Le prerelease di `dev` sono escluse.
L’upload segue la release nello stesso workflow: una release creata con
`GITHUB_TOKEN` non avvia normalmente un secondo workflow basato su `release`.

Il versionCode deve superare quelli già usati in Play Console. Google può comunque
richiedere revisione; la pubblicazione gestita e le dichiarazioni dell’app restano
sotto il controllo della Console. Se ci sono già modifiche in revisione, lo script
si ferma invece di annullarle. Un errore Play non elimina la release GitHub e può
essere affrontato con il workflow manuale, verificando prima cosa è già stato
caricato in Console.

La prima distribuzione pubblica in produzione resta una decisione separata.
Questa configurazione riguarda i canali di test.

## Riferimenti ufficiali

- [Accesso all’API e account di servizio](https://developers.google.com/android-publisher/getting_started)
- [Prerequisiti e funzionamento degli edit](https://developers.google.com/android-publisher/edits)
- [Canali e pubblicazione](https://developers.google.com/android-publisher/tracks)
- [Permessi Play Console](https://support.google.com/googleplay/android-developer/answer/9844686)
- [Creazione della chiave JSON](https://cloud.google.com/iam/docs/keys-create-delete)
- [Eventi generati da GITHUB_TOKEN](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
