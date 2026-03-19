# Order-Book
Il progetto consiste nell’implementazione di un order book, e si occupa quindi 
della gestione degli ordini di acquisto o vendita che vengono eseguiti dagli 
utenti della piattaforma. È articolato in server e client, con il primo che ha il 
compito di gestire effettivamente l’order book e il secondo che dà modo agli 
utenti di registrarsi, autenticarsi e inviare gli ordini al server.  
Il client possiede quattro classi, rispettivamente per:  
• ClientMain: gestisce l’interazione con l’utente, il quale prima può fare 
le operazioni relative all’account, e solo dopo essersi loggato può 
eseguire ordini.  
• JsonObj: si occupa di parsare e trasformare in oggetti json ciò che 
l’utente passa in input; ogni possibile operazione ha il suo metodo, 
tranne alcune che possono condividerlo poiché molto simili.  
• ClientHandler: gestisce la connessione TCP con il client e ha metodi 
per l’invio e ricezione di messaggi e per la chiusura della connessione.  
• AsynchronousNotification: classe che gestisce la ricezione di notifiche 
asincrone che vengono inviate dal server nel caso vengano evasi 
ordini commissionati in precedenza. Viene avviata da un thread 
daemon separato, in modo che possa essere sempre in ascolto, e che 
nel caso di logout dell’utente, il programma si possa chiudere 
facilmente, evitando che il suddetto thread continui ad operare.  
Invece il server ha una struttura più complessa, poiché multithreading, visto 
che deve avviare un thread per ogni client che si connette. Le classi sono:  
• ServerMain: legge e salva tutti i registri e dati che servono per la 
gestione delle operazioni del client, e avvia un thread per ogni 
connessione TCP stabilita.  
• ServerHandler: gestisce le operazioni che vengono richieste dal client 
e che deve svolgere il thread. Implementa anche metodo per l’invio di 
messaggi sulla connessione TCP o notifiche sulla connessione UDP   
• AcountActions: si occupa di gestire le operazioni che si possono 
svolgere senza essere loggati e che poi portano l’utente a loggarsi.  
• OrderHandler: classe che gestisce gli ordini, all’interno ha metodi per 
l’implementazione dell’algoritmo di matching, per ordinare gli array o 
per verificare il prezzo in modo da lanciare o meno gli stop order.  
• FileHandler: questa classe viene usata per l’apertura dei file .json che 
implementano i vari order book distinti in ask e bid e in tipo di ordine.  
Sono presenti metodi per il salvataggio delle strutture dati impiegate 
nel file corrispondente.  
• OrderIdGenerator: classe impiegata per la creazione e l’incremento 
del contatore atomico usato per assegnare l’ID agli ordini  
• AsyncNotification: contiene il metodo per l’invio delle notifiche 
asincrone ai client.  
Il 
server è caratterizzato dal multithreading, ovvero per ogni client che si 
connette viene creato un thread che si occupa di soddisfarne le richieste, e in 
più, quando usiamo il metodo notifyUser, viene creato un ulteriore thread in 
modo che da non bloccare o impiegare il thread rappresentante il client 
nell’invio delle notifiche asincrone. Come già detto prima, questa logica è simile 
a quella usata nel client, ovvero di creare un thread specializzato nelle notifiche 
e nella connessione UDP.  
Nel server, l’accesso a risorse condivise è sincronizzato grazie a due lock 
condivise da tutti i thread, una per l’accesso a users.json e una per l’accesso 
a tutti gli order book. Le lock sono due oggetti statici appartenenti alla classe 
ServerHandler, perciò “appartengono” alla classe e non alla singola istanza.  
Nei due file serverConfigurationData.txt e clientConfigurationData.txt, sono 
presenti appunto dati di configurazione del server e del client, come la porta 
TCP, la dimensione del threadpool, il salvataggio del contatore per il server, o 
sempre la porta TCP e l’indirizzo del client. Questi dati sono modificabili a 
discrezione dell’utente.  
Le 
strutture 
dati utilizzate sono principalmente jsonArray e una 
Map<String,JsonObject> per il registro degli utenti, in questo modo si può 
utilizzare il metodo contains, utile per il controllo della presenza di un username 
o meno tra gli account registrati.  
Per quanto riguarda la compilazione, la zip contiene la cartella src con 
ServerMain.java,  
ClientMain.java,  serverConfigurationData.txt  e 
clientConfigurationData.txt e la cartella lib con la libreria gson-2.10.1, quindi 
le istruzioni per compilare i due main sono le seguenti:  
• Linux: mkdir -p build             
build src/*.java  
• Windows: mkdir build  
javac -cp lib/gson-2.10.1.jar -d 
javac -cp lib\gson-2.10.1.jar -d build src\*.java  
Si compila da dentro la cartella principale, si crea la cartella build, che conterrà 
tutti i file .class creati dalla compilazione dei file della cartella src.  
Le indicazioni per gli argomenti da passare al client vengono stampate appena 
il client viene avviato e si connette al server. Scrivere le operazioni nello stesso 
modo in cui sono indicate ed evitare spazi finali sennò non vengono 
riconosciute
