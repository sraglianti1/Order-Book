import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.google.gson.*;
import java.io.*;
import java.net.*;


 
public class ServerMain {
    static AtomicInteger counter;
    public static void main(String[] args) {
        try{
            //leggo da serverConfigurationData.txt la porta TCP, la dimensione del threadpool e il contatore per l'ID degli ordini
            Properties serverConfigurationData = new Properties();
            FileInputStream stream = new FileInputStream("src/serverConfigurationData.txt");
            serverConfigurationData.load(stream);
            int TCPport = Integer.parseInt(serverConfigurationData.getProperty("TCPport"));
            int poolSize = Integer.parseInt(serverConfigurationData.getProperty("threadPoolSize"));
            int OrderId = Integer.parseInt(serverConfigurationData.getProperty("IdCounter"));
            counter = new AtomicInteger(OrderId);

            //per ogni connessione TCP che viene accettata creo un thread che rappresenta il client
            ExecutorService threadPool = Executors.newFixedThreadPool(poolSize);
            //creo il server socket e attendo che i client si connettano, e per ognuno creo un thread
            try (ServerSocket server = new ServerSocket(TCPport)){
                while(true){
                    Socket client = server.accept();
                    //setto un timeout per la risposta del client
                    client.setSoTimeout(500000);
                    System.out.println("New client connected: " + client.getRemoteSocketAddress());
                    threadPool.submit(new ServerHandler(TCPport, client, counter));
                }
            }
        }
        catch(IOException e){
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }
}

    //classe che gestisce i thread
class ServerHandler implements Runnable {
    //variabili per le connessioni tcp
    protected int TCPport;
    protected Socket client;
    private BufferedReader in;
    private BufferedWriter out;
    //variabili per la gestione dell'utente attualmente loggato che sta usufruendo del client
    AtomicInteger OrderId;
    String currentUser = null;
    Boolean logged = false;
    //apertura dei file che registrano gli ordini e lock per le variabili condivise
    static FileHandler fileHandler = new FileHandler();
    //file che tiene in memoria gli utenti che si sono registrati
    static JsonArray accounts = fileHandler.FileOpener("users.json");
    //file che tiene lo storico degli ordini
    static JsonArray orders = fileHandler.FileOpener("ordersHistory.json");
    //files per memomrizzare i book relativi a ask e bid
    static JsonArray bid = fileHandler.FileOpener("bid.json");
    static JsonArray ask = fileHandler.FileOpener("ask.json");
    static JsonArray StopBidBook = fileHandler.FileOpener("StopBid.json");
    static JsonArray StopAskBook = fileHandler.FileOpener("StopAsk.json");
    //lock per l'accesso sincronizzato al file degli utenti registrati
    static final Object accountsLock = new Object();
    //lock per l'accesso sincronizzato alle risorse usate per le operazioni relative agli ordini
    static final Object lock = new Object();
    
    public ServerHandler(int TCPport, Socket client, AtomicInteger OrderId) {
        this.TCPport = TCPport;
        this.client = client;
        this.OrderId = OrderId;
    }
    
    @Override
    public void run() {
        try {
            //creo i canali per la ricezione e l'invio di messaggi con il client
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));

            String jsonString;
            accountActions responseaccount = new accountActions();
            OrdersHandler order = new OrdersHandler();

            
    
            // leggo gli input dal client, li parso e elaboro le richieste
            while (true) {
                try {
                    jsonString = in.readLine();
                    //se l'input è null esco dal ciclo
                    if (jsonString == null) break; 
                    
                } catch (SocketTimeoutException e) {
                    // nel caso della scadenza del timeout, faccio logout, esco dal ciclo e chiudo la connessione
                    if (logged && currentUser != null) {
                        // Notifica al client
                        JsonObject logoutMsg = new JsonObject();
                        logoutMsg.addProperty("operation", "logout");
                        logoutMsg.addProperty("message", "Timeout expired, you have been logged out.");
                        sendMessage(logoutMsg);
    
                        System.out.println("User " + currentUser + " logged out due to inactivity.");
    
                        JsonObject logoutJson = new JsonObject();
                        logoutJson.addProperty("operation", "logout");
                        responseaccount.Actions(logoutJson, this);
                    }
                    break;
                }
    
                // Faccio il parsing della stringa in input, nel caso di errore invio il messaggio corrispondente al client
                JsonObject json;
                try {
                    json = JsonParser.parseString(jsonString).getAsJsonObject();
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("response", 400);
                    err.addProperty("errorMessage", "Invalid JSON");
                    sendMessage(err);
                    continue;
                }    
    
                // Controllo se è presente il campo "operation"
                if (!json.has("operation") || json.get("operation").isJsonNull()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("response", 400);
                    err.addProperty("errorMessage", "Missing 'operation' field");
                    sendMessage(err);
                    continue;
                }

                String operation = json.get("operation").getAsString();
    
                //Gestione dei comandi per l'accesso o la modifica dell'account, una volta gestita rinizio il ciclo
                if (operation.equals("login") || operation.equals("register") ||
                    operation.equals("logout") || operation.equals("updateCredentials")) {
    
                    JsonObject response = responseaccount.Actions(json, this);
                    sendMessage(response);
                    
                    //nel caso di logout, faccio return e termino il thread
                    if (operation.equals("logout") && response.get("response").getAsInt() == 100) {
                        System.out.println("User " + currentUser + " disconnected.");
                        if (client != null && !client.isClosed()) {
                            client.close();
                        }
                        return;
                    }
                    continue;
                }
    
                //se l'utente non è loggato, ricomincio il ciclo
                if ((operation.equals("insertLimitOrder") ||
                     operation.equals("insertMarketOrder") ||
                     operation.equals("insertStopOrder")) && currentUser == null) {
    
                    JsonObject err = new JsonObject();
                    err.addProperty("response", 401);
                    err.addProperty("errorMessage", "You must be logged in to place an order");
                    sendMessage(err);
                    continue;
                }
    
                //Una volta loggato, l'utente può fare le seguente operazioni
                switch (operation) {
                    case "insertLimitOrder": {
                        JsonObject response = order.insertLimitOrder(json, this);
                        sendMessage(response);
                        break;
                    }
                    case "insertMarketOrder": {
                        JsonObject response = order.insertMarketOrder(json, this);
                        sendMessage(response);
                        break;
                    }
                    case "insertStopOrder": {
                        order.insertStopOrder(json, this);
                        JsonObject response = new JsonObject();
                        response.addProperty("response", 100);
                        response.addProperty("errorMessage", "Stop order received");
                        sendMessage(response);
                        break;
                    }
                    case "CancelOrder": {
                        JsonObject response = order.CancelOrder(json, this);
                        sendMessage(response);
                        break;
                    }
                    case "getPriceHistory" :{
                        JsonObject response = order.getPriceHistory(json, this);
                        sendMessage(response);
                        break;
                    }
                    default: {
                        JsonObject err = new JsonObject();
                        err.addProperty("response", 404);
                        err.addProperty("errorMessage", "Unknown operation: " + operation);
                        sendMessage(err);
                        break;
                    }
                }
            }
        } 
        catch (IOException e) {
            System.err.println("Errore di I/O con il client " + client.getRemoteSocketAddress() + ": " + e.getMessage());
        } 
        catch (Exception e) {
            System.err.println("Errore imprevisto con il client " + client.getRemoteSocketAddress());
            e.printStackTrace();
        } 
        finally {
            try {
                if (client != null && !client.isClosed()) {
                    client.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    
    //metodo per inviare un messaggio sulla conessione tcp
    public void sendMessage(JsonObject message) {
        try {
            out.write(message.toString());
            out.newLine();
            out.flush();
        } catch (IOException e) {
            System.err.println("Errore nell'invio a " + currentUser);
            e.printStackTrace();
        }
    }

    //Mertodo per inviare una notifica all'utente tramite UDP
    public static void notifyUser(JsonObject message, String username) {
        for (JsonElement i : ServerHandler.accounts) {
            if (i == null || !i.isJsonObject()) continue;
            JsonObject obj = i.getAsJsonObject();
            if (username.equals(obj.get("username").getAsString())) {
                String address = obj.get("address").getAsString();
                int UDPport = obj.get("UDPport").getAsInt();
    
                // invio UDP in un thread separato in modo da non bloccare il thread che gestisce ordini/account in caso di problemi
                new Thread(() -> {
                    try {
                        AsyncNotification.sendNotification(address, UDPport, message);
                    } catch (Exception e) {
                        System.out.println("Asynchronous notification error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }).start();
    
                break;
            }
        }
    }
}


    //Classe per la gestione delle operazioni legate all'account/accesso
    class accountActions{
        Gson gson = new Gson();
        public JsonObject Actions(JsonObject operation, ServerHandler handler){
            JsonObject response = new JsonObject();
            JsonObject values = operation.getAsJsonObject("values");

            synchronized(ServerHandler.accountsLock){
                try{
                    //inserisco tutti gli account di users.json nella map accounts per poter fare modifiche e controlli
                    Map<String, JsonObject> accounts = new HashMap<>();
                        for (JsonElement i : ServerHandler.accounts) {
                            JsonObject obj = i.getAsJsonObject();
                            String username = obj.get("username").getAsString();
                            accounts.put(username, obj);
                        }
                    //gestione delle varie operazioni
                    switch(operation.get("operation").getAsString()){

                        case "login":{
                            if (!values.has("username") || values.get("username").isJsonNull() || values.get("username").getAsString().isEmpty()) {
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "username/password mismatch or non existent username");
                                return response;   
                            }
                            if(!values.has("password") || values.get("password").isJsonNull() || values.get("password").getAsString().isEmpty()){
                                response.addProperty("response", 103);
                                response.addProperty("errorMessage", "invalid password");
                                return response;}
                            String username = values.get("username").getAsString();
                            String password = values.get("password").getAsString();
                            if (accounts.containsKey(username)) {
                                JsonObject useraccount = accounts.get(username);
                                if (useraccount.get("password").getAsString().equals(password)){
                                    if (useraccount.has("logged") && useraccount.get("logged").getAsString().equals("yes")) {
                                        response.addProperty("response", 102);
                                        response.addProperty("errorMessage", "user already logged in");
                                        return response;
                                    }
                                    else{
                                        useraccount.addProperty("address",handler.client.getInetAddress().getHostAddress());
                                        useraccount.addProperty("TCPport", handler.TCPport);
                                        useraccount.addProperty("UDPport",operation.get("UDPport").getAsInt());
                                        useraccount.addProperty("logged", "yes");
                                        response.addProperty("response", 100);
                                        response.addProperty("errorMessage", "OK");
                                        handler.currentUser = username;
                                        handler.logged = true;
                                        //se l'utente esiste e non è loggato, sovrascrivo users.json con logged yes
                                        ServerHandler.accounts =ServerHandler.fileHandler.FileMapSaver("users.json",accounts);
                                        return response;
                                    }
                            }
                            else{
                                    response.addProperty("response", 101);
                                    response.addProperty("errorMessage", "username/password mismatch or non existent username");
                                    return response;
                                }
                            }
                            else{
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "username/password mismatch or non existent username");
                                return response;
                            }
                        }
                        case "register":{
                            if (!values.has("username") || values.get("username").isJsonNull() || values.get("username").getAsString().isEmpty()) {
                                response.addProperty("response", 103);
                                response.addProperty("errorMessage", "invalid username");
                                return response;   
                            }
                            if(!values.has("password") || values.get("password").isJsonNull() || values.get("password").getAsString().isEmpty()){
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "invalid password");
                                return response;}

                            String username = values.get("username").getAsString();

                            if (accounts.containsKey(username)) {
                                response.addProperty("response", 102);
                                response.addProperty("errorMessage", "username not available");
                                return response;
                            }
                            //se utente nuovo e username e password approvati, registro l'utente in user.json e lo loggo
                            else{JsonObject useraccount = new JsonObject();
                                useraccount.addProperty("address",handler.client.getInetAddress().getHostAddress());
                                useraccount.addProperty("TCPport", handler.TCPport);
                                useraccount.addProperty("UDPport",operation.get("UDPport").getAsInt());
                                useraccount.addProperty("username", username);
                                useraccount.addProperty("password", values.get("password").getAsString());
                                useraccount.addProperty("logged", "yes");
                                accounts.put(username, useraccount);
                                response.addProperty("response", 100);
                                response.addProperty("errorMessage", "OK");
                                handler.currentUser = username;
                                handler.logged = true;
                                ServerHandler.accounts =ServerHandler.fileHandler.FileMapSaver("users.json",accounts);
                                
                                return response;
                            }
                        }
                        case "logout":{
                            if (accounts.containsKey(handler.currentUser)) {
                                JsonObject useraccount = accounts.get(handler.currentUser);
                                if(useraccount.has("logged") && useraccount.get("logged").getAsString().equals("yes")){
                                    useraccount.addProperty("logged", "no");
                                    response.addProperty("response", 100);
                                    response.addProperty("errorMessage", "OK");
                                    handler.currentUser = null;
                                    handler.logged = false;
                                    //setto logged a no
                                    ServerHandler.accounts =ServerHandler.fileHandler.FileMapSaver("users.json",accounts);
                                    return response;
                                }
                                else{
                                    response.addProperty("response", 101);
                                    response.addProperty("errorMessage", "user not logged");
                                    return response;
                                }
                            }
                            else{
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "user not logged");
                                return response;
                            }
                        }
                        case "updateCredentials":{
                            if (!values.has("username") || values.get("username").isJsonNull() || values.get("username").getAsString().isEmpty()) {
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "username/password mismatch or non existent usernam");
                                return response;   
                            }
                            if(!values.has("old_password") || values.get("old_password").isJsonNull() || values.get("old_password").getAsString().isEmpty()){
                                response.addProperty("response", 102);
                                response.addProperty("errorMessage", "username/old_password mismatch or non existent username");
                                return response;}
                            if(!values.has("new_password") || values.get("new_password").isJsonNull() || values.get("new_password").getAsString().isEmpty()){
                                response.addProperty("response", 101);
                                response.addProperty("errorMessage", "invalid new password");
                                return response;}
                            String username = values.get("username").getAsString();
                            String oldPassword = values.get("old_password").getAsString();
                            String newPassword = values.get("new_password").getAsString();
                            if(accounts.containsKey(username)){
                                JsonObject useraccount = accounts.get(username);
                                if(!oldPassword.equals(newPassword)){
                                    if(useraccount.get("password").getAsString().equals(oldPassword)){
                                        if(!useraccount.has("logged") || !useraccount.get("logged").getAsString().equals("yes")){
                                            useraccount.addProperty("address",handler.client.getInetAddress().getHostAddress());
                                            useraccount.addProperty("TCPport", handler.TCPport);
                                            useraccount.addProperty("logged", "yes");
                                            useraccount.addProperty("password", newPassword);
                                            response.addProperty("response", 100);
                                            response.addProperty("errorMessage", "OK");
                                            handler.currentUser = username;
                                            handler.logged = true;
                                            //se passa i controlli, salvo nuova password e loggo l'utente
                                            ServerHandler.accounts =ServerHandler.fileHandler.FileMapSaver("users.json",accounts);
                                            return response;}
                                            else{
                                                response.addProperty("response", 104);
                                                response.addProperty("errorMessage", "user currently logged in");
                                                return response;
                                            }
                                    }
                                    else{
                                        response.addProperty("response", 102);
                                        response.addProperty("errorMessage", "username/old_password mismatch or nonexistent username");
                                        return response;  
                                    }
                                }
                                else{
                                    response.addProperty("response", 103);
                                    response.addProperty("errorMessage", "new passord equal to old one");
                                    return response;
                                }
                            }
                            else{
                                response.addProperty("response", 102);
                                        response.addProperty("errorMessage", "username/old_password mismatch or nonexistent username");
                                        return response;  
                            }
                        }
                                                      
                    }
                }catch(IOException e){
                    System.out.println(e.getMessage());
                    e.getStackTrace();
                }
            }
            return response;
        }
    }

    //Classe per la gestione degli ordini
    class OrdersHandler {
        // Metodo generico per eseguire il matching
        private JsonObject matchOrder(JsonObject order, ServerHandler handler) {
            synchronized (ServerHandler.lock) {
                try {
                    JsonObject values = order.get("values").getAsJsonObject();
                    String type = values.get("type").getAsString();
                    String orderType = order.get("operation").getAsString();
                    JsonArray oppositeBook = type.equals("bid") ? ServerHandler.ask : ServerHandler.bid;
                    int remaining = values.get("size").getAsInt();
                    
                    int orderId;

                    // assegno l'ID all'ordine
                    if(!values.has("orderId")){
                         orderId = ServerMain.counter.incrementAndGet();
                        SaveOrderId(orderId);
                        values.addProperty("orderId", orderId);
                    }
                    orderId = values.get("orderId").getAsInt();

                    values.addProperty("orderType", orderType);
        
                    // restituisco -1 se market order non può essere eseguito
                    if (orderType.equals("insertMarketOrder") && 
                        (oppositeBook.size() <= 1 || oppositeBook.get(0).getAsJsonObject().get("totalAvailable").getAsInt() < remaining)) {
                        values.addProperty("orderId", -1);
                        return values;
                    }
        
                    // ciclo per il matching degli ordini; se l'ordine viene completamente evaso o non c'è più disponibilità, interrompo
                    while (remaining > 0 && oppositeBook.size() > 1) {
                        JsonObject opp = oppositeBook.get(1).getAsJsonObject();
                        
                        //continuo se è insertMarket o se si può fare il match sul prezzo, altrimenti esco
                        boolean priceMatch = (type.equals("bid") && 
                                              (orderType.equals("insertMarketOrder") || values.get("price").getAsInt() >= opp.get("price").getAsInt()))
                                          || (type.equals("ask") && 
                                              (orderType.equals("insertMarketOrder") || values.get("price").getAsInt() <= opp.get("price").getAsInt()));
        
                        if (!priceMatch) {
                            break; 
                        }
                        
                        //definisco la quantità dello scambio e aggiorno remaining
                        int traded = Math.min(remaining, opp.get("size").getAsInt());
                        remaining -= traded;
        
                        // aggiorno totalavailable, cioè la disponibilità nel book
                        JsonObject totalAvailableObj = oppositeBook.get(0).getAsJsonObject();
                        totalAvailableObj.addProperty("totalAvailable", totalAvailableObj.get("totalAvailable").getAsInt() - traded);
        
                        // aggiorno l'oggetto che rappresenta l'ordine nel book
                        if (opp.get("size").getAsInt() == traded) {
                            oppositeBook.remove(1); 
                        } else {
                            opp.addProperty("size", opp.get("size").getAsInt() - traded);
                            oppositeBook.set(1, opp);
                        }
        
                        // aggiorno book e storico degli ordini
                        ServerHandler.fileHandler.FileBookSaver(type.equals("bid") ? "ask.json" : "bid.json", oppositeBook);
                        
                        JsonObject trade = new JsonObject();
                        trade.addProperty("orderId", orderId);
                        trade.addProperty("type", type);
                        trade.addProperty("orderType", orderType);
                        trade.addProperty("size", traded);
                        trade.addProperty("price", opp.get("price").getAsInt());
                        trade.addProperty("timestamp", Instant.now().getEpochSecond());
                        
                        ServerHandler.orders.add(trade);
                        ServerHandler.fileHandler.FileBookSaver("ordersHistory.json", ServerHandler.orders);
        
                        // notifico gli utenti coinvolti con tramite mnotifica UDP
                        JsonObject message = new JsonObject();
                        message.addProperty("notification", "closedTrades");
                        message.add("trades", trade);
                        ServerHandler.notifyUser(message, handler.currentUser);
                        if (!opp.get("username").getAsString().equals(handler.currentUser)) {
                            ServerHandler.notifyUser(message, opp.get("username").getAsString());
                        }
                    }
        
                    // gestione residui per limit order e aggiorno il book
                    if (remaining > 0 && orderType.equals("insertLimitOrder")) {
                        values.addProperty("size", remaining);
                        values.addProperty("username", handler.currentUser);
                        values.addProperty("timestamp", Instant.now().getEpochSecond());
                        JsonArray myBook = type.equals("bid") ? ServerHandler.bid : ServerHandler.ask;
                        insertIntoOrderBook(myBook, values, type); 
                    }
        
                    // dopo aggiornamento book, controllo se uno stopOrder è eseguibile
                    checkStopOrders(handler);
                    
                    //aggiorno la dimensione dell'ordine fatto dal client
                    values.addProperty("size", remaining); 
                    return values;
        
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                    e.getStackTrace();
                    return new JsonObject();
                }
            }
        }

        //metodo per controllare se stoporder eseguibile
        private void checkStopOrders(ServerHandler handler) {
            int bestAsk = getBestPrice("ask");
            int bestBid = getBestPrice("bid");
        
            //se match, trasformo stop bid in market order
            Iterator<JsonElement> itBid = ServerHandler.StopBidBook.iterator();
            while(itBid.hasNext()) {
                JsonObject stopOrder = itBid.next().getAsJsonObject();
                int stopPrice = stopOrder.get("values").getAsJsonObject().get("price").getAsInt();
                if (bestAsk > 0 && bestAsk <= stopPrice) {
                    stopOrder.addProperty("operation", "insertMarketOrder");
                    matchOrder(stopOrder, handler);
                    itBid.remove();
                }
            }
            try {
                ServerHandler.fileHandler.FileBookSaver("StopBid.json", ServerHandler.StopBidBook);
            } catch (IOException e) {
                e.printStackTrace();
            }
        
            //se match, trasformo stop ask in market order
            Iterator<JsonElement> itAsk = ServerHandler.StopAskBook.iterator();
            while(itAsk.hasNext()) {
                JsonObject stopOrder = itAsk.next().getAsJsonObject();
                int stopPrice = stopOrder.get("values").getAsJsonObject().get("price").getAsInt();
                if (bestBid > 0 && bestBid >= stopPrice) {
                    stopOrder.addProperty("operation", "insertMarketOrder");
                    matchOrder(stopOrder, handler);
                    itAsk.remove();

                }
            }
            try {
                ServerHandler.fileHandler.FileBookSaver("StopAsk.json", ServerHandler.StopAskBook);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //ordini o azioni possibili, per ognuno eseguo matchorder
        public JsonObject insertMarketOrder(JsonObject order, ServerHandler handler) {
            order.addProperty("operation", "insertMarketOrder");
            return matchOrder(order, handler);
        }
        
        public JsonObject insertLimitOrder(JsonObject order, ServerHandler handler) {
            order.addProperty("operation", "insertLimitOrder");
            return matchOrder(order, handler);
        }
        

        public JsonObject insertStopOrder(JsonObject order, ServerHandler handler) {
            JsonObject values = order.get("values").getAsJsonObject();
            order.addProperty("operation", "insertStopOrder");
            values.addProperty("username", handler.currentUser);
            values.addProperty("timestamp", Instant.now().getEpochSecond());
        
            String side = values.get("type").getAsString();
            int stopPrice = values.get("price").getAsInt();
            int ref = getBestOppositePrice(side);        
            boolean trigger = shouldTrigger(side, ref, stopPrice);
        
            //se condizioni stop order soddisfatte, lancio market order
            if (trigger) {
                order.addProperty("operation", "insertMarketOrder");
                return matchOrder(order, handler);
            }
            
            //se size valida, inserisco lo stop order nel registro corrispondente
            if(values.get("size").getAsInt() > 0) {
                int orderId = handler.OrderId.incrementAndGet();
                values.addProperty("orderId", orderId);
                SaveOrderId(orderId);
                if ("bid".equals(side)) {
                    ServerHandler.StopBidBook.add(order.deepCopy());
                    ServerHandler.StopBidBook = sortJsonArray(ServerHandler.StopBidBook,Comparator.comparing(o -> o.get("values").getAsJsonObject().get("timestamp").getAsLong()));
                    try {
                        ServerHandler.fileHandler.FileBookSaver("StopBid.json", ServerHandler.StopBidBook);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    ServerHandler.StopAskBook.add(order.deepCopy());
                    ServerHandler.StopAskBook = sortJsonArray(ServerHandler.StopAskBook, Comparator.comparing(o -> o.get("values").getAsJsonObject().get("timestamp").getAsLong()));
                    try {
                        ServerHandler.fileHandler.FileBookSaver("StopAsk.json", ServerHandler.StopAskBook);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            //la risposta che arriva al client è che l'ordine è stato correttamente inserito nel registro
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Stop order queued");
            response.addProperty("orderId", values.get("orderId").getAsInt());
            return response;
        }

        //metodo per ottenere lo storico di un determinato mese
        public JsonObject getPriceHistory(JsonObject order, ServerHandler handler){

            String inputMonth =  order.get("values").getAsJsonObject().get("month").getAsString();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMyyyy");

            //Conversione in yearmonth
            YearMonth ym = YearMonth.parse(inputMonth, fmt);
            int daysInMonth = ym.lengthOfMonth();

            //creo jsonarray con un oggetto per giorno del mese
            JsonArray monthHistory = new JsonArray();
            for(int d = 0; d < daysInMonth; d++){
                JsonObject day = new JsonObject();
                day.addProperty("day", d+1);
                day.addProperty("opening price", -1);
                day.addProperty("closing price", -1);
                day.addProperty("max price", -1);
                day.addProperty("min price", -1);
                monthHistory.add(day);
            }

            ZoneId zone = ZoneId.of("GMT");
            //lock in modo che lo storico non subisca modifiche mentre lo leggo
            synchronized(ServerHandler.lock){
                for(JsonElement elem : ServerHandler.orders){
                    JsonObject o = elem.getAsJsonObject();
                    long ts = o.get("timestamp").getAsLong();
                    int price = o.get("price").getAsInt();

                    // Converto il timestamp in giorno del mese
                    LocalDateTime orderDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime();
                    int dayIndex = orderDate.getDayOfMonth() - 1;

                    //se il giorno è valido e minore del numero dei giorni nel mese richiesto, continuo il ciclo
                    if(dayIndex >= 0 && dayIndex < daysInMonth){
                        JsonObject dayObj = monthHistory.get(dayIndex).getAsJsonObject();
                        
                        //prezzo d'apertura
                        if(dayObj.get("opening price").getAsInt() == -1){
                            dayObj.addProperty("opening price", price);
                        }

                        //prezzo di chiusura
                        dayObj.addProperty("closing price", price);

                        //prezzo massimo
                        int max = dayObj.get("max price").getAsInt();
                        if(max == -1 || price > max){
                            dayObj.addProperty("max price", price);
                        }

                        //prezzo minimo
                        int min = dayObj.get("min price").getAsInt();
                        if(min == -1 || price < min){
                            dayObj.addProperty("min price", price);
                        }
                    }
                }
            }

            //rimuovo ogni giorno in cui non ci sono stati ordini
            for (int i = monthHistory.size() - 1; i >= 0; i--) {
                JsonObject day = monthHistory.get(i).getAsJsonObject();
                if(day.get("opening price").getAsInt() == -1){
                    monthHistory.remove(i);
                }
            }

            JsonObject response = new JsonObject();

            
            if(monthHistory.size()==0){
                //risposta nel caso in cui nel mese non ci siano stati ordini
                response.addProperty("errorMessage: ", "no orders in this month");
            }
            response.add("month history", monthHistory);
            return response;
        }

        //metodo per cancellare un ordine non ancora evaso
        public JsonObject CancelOrder(JsonObject order, ServerHandler handler){
            int CancelId = order.get("values").getAsJsonObject().get("orderId").getAsInt();

            synchronized(ServerHandler.lock){
                //scorro gli elementi di ogni registro per vedere se trovo l'ordine, e in quel caso lo cancello e ritorno la risposta affermativa

                for (JsonElement i : ServerHandler.ask){
                    JsonObject toCancel = i.getAsJsonObject();

                    if(toCancel.has("orderId")){
                        if(CancelId == toCancel.get("orderId").getAsInt()){
                            int total = ServerHandler.ask.get(0).getAsJsonObject().get("totalAvailable").getAsInt();
                            ServerHandler.ask.get(0).getAsJsonObject().addProperty("totalAvailable", total - toCancel.get("size").getAsInt());  
                            ServerHandler.ask.remove(i); 
                            try{ServerHandler.fileHandler.FileBookSaver("ask.json", ServerHandler.ask);}
                            catch(IOException e){
                                System.out.print(e.getMessage());
                                e.getStackTrace();
                            }
                            JsonObject response = new JsonObject();
                            response.addProperty("response", 100);
                            response.addProperty("errorMessage", "OK");
                            return response;
                        }
                    }
                }
                for (JsonElement i : ServerHandler.bid){
                    JsonObject toCancel = i.getAsJsonObject();
                    if(toCancel.has("orderId")){
                        if(CancelId == toCancel.get("orderId").getAsInt()){
                            int total = ServerHandler.bid.get(0).getAsJsonObject().get("totalAvailable").getAsInt();
                            ServerHandler.bid.get(0).getAsJsonObject().addProperty("totalAvailable", total - toCancel.get("size").getAsInt());  
                            ServerHandler.bid.remove(i); 
                            try{ServerHandler.fileHandler.FileBookSaver("bid.json", ServerHandler.bid);}
                            catch(IOException e){
                                System.out.print(e.getMessage());
                                e.getStackTrace();
                            }
                            JsonObject response = new JsonObject();
                            response.addProperty("response", 100);
                            response.addProperty("errorMessage", "OK");
                            return response;
                        }
                    }
                }
                Iterator<JsonElement> iterAsk = ServerHandler.StopAskBook.iterator();
                while(iterAsk.hasNext()){
                    if(CancelId == iterAsk.next().getAsJsonObject().get("values").getAsJsonObject().get("orderId").getAsInt()){
                        iterAsk.remove();
                        try {
                            ServerHandler.fileHandler.FileBookSaver("StopAsk.json", ServerHandler.StopAskBook);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        JsonObject response = new JsonObject();
                        response.addProperty("response", 100);
                        response.addProperty("errorMessage", "OK");
                        return response;
                    }
                }
                Iterator<JsonElement> iterBid = ServerHandler.StopBidBook.iterator();
                while(iterBid.hasNext()){
                    if(CancelId == iterBid.next().getAsJsonObject().get("values").getAsJsonObject().get("orderId").getAsInt()){
                        iterBid.remove();
                        try {
                            ServerHandler.fileHandler.FileBookSaver("StopBid.json", ServerHandler.StopBidBook);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        JsonObject response = new JsonObject();
                        response.addProperty("response", 100);
                        response.addProperty("errorMessage", "OK");
                        return response;
                    }
                }
            }

            //se non trovo l'ordine, comunico al client che l'ordine non esiste o e stato evaso o appartiene a un altro utente
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "order does not exist or belongs to different user or has already been finalized");
            return response;
        }
        
        //metodo per otttenere il primo prezzo del book, ovvero il più vantaggioso
        private int getBestPrice(String side){
            JsonArray book = side.equals("bid") ? ServerHandler.bid : ServerHandler.ask;
        
            if (book.size() > 1) {
                int p = book.get(1).getAsJsonObject().get("price").getAsInt();
                return p;
            }

            return 0;
        }
        
        //metodo per ottenere il miglior prezzo del book opposto al tipo dell'ordine
        private int getBestOppositePrice(String side){
            return getBestPrice(side.equals("bid") ? "ask" : "bid");
        }

        //metodo per controllare se lo stop order può "diventare market", cioè attivarsi
        private boolean shouldTrigger(String type, int refPrice, int stopPrice) {
            if ("bid".equals(type)) {
                return refPrice > 0 && refPrice <= stopPrice;
            } else {
                return refPrice > 0 && refPrice >= stopPrice;
            }
        }

        //metodo per l'inserimento di un limit order nel book corrispondente
        private void insertIntoOrderBook(JsonArray book, JsonObject values, String type) {
            JsonObject totalAvailable = book.get(0).getAsJsonObject();
            totalAvailable.addProperty("totalAvailable", totalAvailable.get("totalAvailable").getAsInt() + values.get("size").getAsInt());
            List<JsonElement> appList = new ArrayList<>();
            book.forEach(appList::add);
            appList.add(1, values);
            //svuoto il book e lo riempo con il nuovo ordine 
            for (int i = book.size() - 1; i >= 0; i--) {
                book.remove(i);
            }
            for (JsonElement e : appList) {
                book.add(e);
            }

            //salvo sul file contenente il registro corrispondente
            try{ServerHandler.fileHandler.FileBookSaver(type.equals("bid")? "bid.json" : "ask.json", book);}
            catch(IOException e){System.out.println(e.getMessage());}
        }

        //metodo per salvare l'orderID in modo che al riavvio del server non riparta da zero
        private void SaveOrderId(int OrderId){
            String file = "src/serverConfigurationData.txt";
            try {
                // Leggi tutte le righe del file
                List<String> righe = Files.readAllLines(Paths.get(file));
                List<String> mod = new ArrayList<>();
    
                for (String riga : righe) {
                    if (riga.startsWith("IdCounter")) {
                        riga = "IdCounter = " + OrderId;
                    }
                    mod.add(riga);
                }

                Files.write(Paths.get(file), mod);
            } catch (IOException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
        
        private JsonArray sortJsonArray(JsonArray array, Comparator<JsonObject> comparator) {
            List<JsonObject> list = new ArrayList<>();
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    list.add(e.getAsJsonObject());
                }
            }
        
            list.sort(comparator);
        
            JsonArray sorted = new JsonArray();
            for (JsonObject obj : list) {
                sorted.add(obj);
            }
        
            return sorted;
        }
    }


    //classe per la gestione, scrittura e lettura dei file; se non esistono restituisce un JsonArray vuoto
    class FileHandler{
        Gson gson = new Gson();
        JsonArray array = new JsonArray();
    
        public JsonArray FileOpener(String file) {
            File f = new File(file);
            if (!f.exists()) {
                try (Writer writer = new FileWriter(f)) {
                    JsonArray newArray = new JsonArray();
                    if (file.equals("ask.json") || file.equals("bid.json")) {
                        // Creo un JsonObject con totalAvailable = 0
                        JsonObject obj = new JsonObject();
                        obj.addProperty("totalAvailable", 0);
                        newArray.add(obj);
                    }
                    // Scrivo l'array nel file
                    gson.toJson(newArray, writer);
                    return newArray;
                }catch(IOException e){
                    System.out.println(e.getMessage());
                }
                return new JsonArray();
            }
            try (Reader reader = new FileReader(f)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonArray()) {
                    // Se il file è vuoto o non contiene un array JSON, ritorna array vuoto
                    return new JsonArray();
                }
                return parsed.getAsJsonArray();
            } catch (IOException e) {
                System.out.println(e.getMessage());
                return new JsonArray();
            }
        }

        //metodo per salavare Map <String,JsonObject> in un file (usato per la gestione di accounts e users.json)
        public JsonArray FileMapSaver(String file, Map <String,JsonObject> datas) throws IOException{
            Gson gson = new Gson(); 
            File f = new File(file); 
            JsonArray array = new JsonArray(); 
            for (JsonObject obj : datas.values()){ 
                array.add(obj); 
            } 
            try (Writer writer = new FileWriter(f)){ 
                gson.toJson(array, writer); 
                return array; 
            } 
        } 
        
        //metodo che salva i book nel file corrispondente
        public void FileBookSaver(String file, JsonArray book) throws IOException{ 
            Gson gson = new Gson();
            File f = new File(file); 
            JsonArray array = new JsonArray(); 
            for (JsonElement obj : book){ 
                array.add(obj.getAsJsonObject()); 
            } 
            try (Writer writer = new FileWriter(f)){ 
                gson.toJson(array, writer); 
            } 
        } 
    }
    

    //classe per la gestione del contatore atomico utilizzato da orderID
    class OrderIdGenerator {
        private AtomicInteger counter = new AtomicInteger(1);
    
        public int nextId() {
            return counter.getAndIncrement();
        }
    }

    //classe per la gestione e l'invio delle notifiche asincrone al client
    class AsyncNotification {
        private static DatagramSocket udpSocket;
        
        //unico socket per l'invio delle notifiche
        static {
            try {
                udpSocket = new DatagramSocket();
                System.out.println("UDP socket ready");
            } catch (SocketException e) {
                System.out.println("UDP socket opening failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    
        private AsyncNotification() {}

        //metodo per l'invio di notifiche 
        public static void sendNotification(String clientAddress, int clientPort, JsonObject message) {
            try {
                byte[] data = message.toString().getBytes(StandardCharsets.UTF_8);
                //return in caso di messaggio con una dimensione troppo grande
                if (data.length > 65507) {
                    System.out.println("ERROR: messagge out of bound " + data.length + " bytes to " + clientAddress);
                    return;
                }
    
                InetAddress address = InetAddress.getByName(clientAddress);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, clientPort);
                
                //invio la notifica e stampo ciò che ho inviato o errori sul terminale del server
                udpSocket.send(packet);
                System.out.println("UDP notification to " + clientAddress + ":" + clientPort + " message: " + message);
            } catch (UnknownHostException e) {
                System.out.println("ERROR: UDP address unknown " + clientAddress + ": " + e.getMessage());
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                System.out.println("ERROR: UDP port invalid " + clientPort + ": " + e.getMessage());
                e.printStackTrace();
            } catch (IOException e) {
                System.out.println("ERROR: UDP packet sent to " + clientAddress + ":" + clientPort + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }