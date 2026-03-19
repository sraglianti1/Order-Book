import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;




public class ClientMain {
    public static void main(String[] args) throws Exception {
        try{        
            //utilizzo properties per parsare le stringhe lette dal file contenente le informazioni del client
            Properties clientConfiguration = new Properties();
            FileInputStream stream = new FileInputStream("src/clientConfigurationData.txt");
            clientConfiguration.load(stream);
            String serverAddress = clientConfiguration.getProperty("serverAddress");
            int TCPport = Integer.parseInt(clientConfiguration.getProperty("TCPport"));

            //rendo possibile la ricezione di notifiche asincrone
            AsynchronousNotification asyncReceiver = new AsynchronousNotification();
            asyncReceiver.setDaemon(true);
            asyncReceiver.start();

            //avvio la connessione con il server tramite l'handler del client  
            ClientHandler handler = new ClientHandler(serverAddress, TCPport);

            //messaggio di inizio con opzioni iniziali di accesso
            Start();

            //variabile per continuare il ciclo while in caso di errore in input, altrimenti esco e continuo

            boolean correctInput = false;

            //input e lista azioni iniziali
            Scanner input = new Scanner(System.in); 

            //ciclo while per la lettura in input delle azioni che vuole fare fare l'utente finchè non inserisce un input valido
            while(!correctInput){
                try{
                    //lettura in input dell'azione scelta
                    String action = input.nextLine();
                    if (action.equals("register") || action.equals("login")) {
                        //creo l'oggetto JsonObj che riceve gli input e l'azione da fare e creo la stringa contenente le informazioni scritte in formato json
                        JsonObj JsonObj = new JsonObj();
                        String jsonString = JsonObj.JSONBuilderReglog(input, action, AsynchronousNotification.UDPport);

                        //invio il JSON al server e ricevo la risposta
                        JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                        System.out.println(response);
                        if (response.get("response").getAsInt() == 100){ 
                            //esco dal ciclo while poiché l'azione scritta in input è corretta e la risposta è 100 - OK
                            correctInput = true;
                            break;
                        }
                        else{
                            //stampo l'errore e la lista di azioni disponibili
                            System.out.println(response.get("response").getAsInt() + response.get("errorMessage").getAsString());
                            Start();   
                        }
                    }
                    //stessa procedura vista sopra
                    else if (action.equals("updateCredentials")){
                        JsonObj JsonObj = new JsonObj();
                        String jsonString = JsonObj.JSONBuilderReglog(input, "updateCredentials", AsynchronousNotification.UDPport);
                        JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                        if (response.get("response").getAsInt() == 100) {
                            correctInput = true;
                        } else {
                            System.out.println(response.get("response").getAsInt() + response.get("errorMessage").getAsString());
                        }
                    }
                    else{    
                        System.out.println("Invalid input, please type 'register', 'login' or 'updateCredentials'.");
                        Start();}
                }
                catch(IllegalArgumentException e){
                    System.out.println(e.getMessage());
                    Start();
                }
            }

            //parte analoga alla prima ma con le altre azioni successive al login/registrazione
            correctInput = false;
            orderActions();
            while(!correctInput){
                try{
                    String action = input.nextLine();
                    System.out.println(action);
                        switch (action) {
                            case "insertLimitOrder": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderInsertOrders(input, "insertLimitOrder");
                                JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                                if (response.get("size").getAsInt() > 0) {
                                    System.out.println("OrderID: "+ response.get("orderId").getAsInt() + " not completely fulfilled");
                                    orderActions();
                                } else if(response.get("size").getAsInt() == 0){
                                    System.out.println("OrderID: " + response.get("orderId").getAsInt() + " completely fulfilled");
                                    orderActions();
                                }
                                break;
                            }
                            case "insertMarketOrder": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderInsertOrders(input, "insertMarketOrder");
                                String reply = handler.sendAndReceive(jsonString);
                                if (reply == null || reply.isEmpty()) {
                                    System.out.println("Error: null response from the server");
                                    continue;
                                }
                                JsonObject response = JsonParser.parseString(reply).getAsJsonObject();                                
                                System.out.println(response);
                                if(response.get("orderId").getAsInt() == -1){
                                    System.out.println("There is not enough availability");
                                    orderActions();
                                }
                                else if(response.get("orderId").getAsInt() != -1 && !response.isEmpty() && !response.get("orderId").isJsonNull()){
                                    System.out.println("Your order orderID: " +  response.get("orderId").getAsInt() + " have been fulfilled");
                                    orderActions();   
                                }

                                break;
                            }
                            case "insertStopOrder": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderInsertOrders(input, action);
                                JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                                if(!response.has("orderId")){
                                    System.out.println("There is not enough availability");
                                    orderActions();}
                                else if(response.get("response").getAsInt() == 100) {
                                    System.out.println("orderID: " + response.get("orderId").getAsInt());
                                    continue;
                                }
                                
                                break;
                            }
                            case "CancelOrder": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderCancelOrder(input, "CancelOrder");
                                JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                                if (response.get("response").getAsInt() == 100) {
                                    System.out.println(response);
                                    continue;
                                } else {
                                    System.out.println(response.get("response").getAsInt() + response.get("errorMessage").getAsString());
                                    orderActions();
                                }
                                break;
                            }
                            case "getPriceHistory": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderHistory(input, "getPriceHistory"); 
                                JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                                if (response.has("month history") && response.getAsJsonArray("month history").size() > 0) {
                                    System.out.println(response);
                                } else {
                                    System.out.println(response.get("response").getAsInt() + response.get("errorMessage").getAsString());
                                    orderActions();
                                }
                                break;
                            }
                            case "logout": {
                                JsonObj JsonObj = new JsonObj();
                                String jsonString = JsonObj.JSONBuilderLogout(input, "logout");
                                JsonObject response = JsonParser.parseString(handler.sendAndReceive(jsonString)).getAsJsonObject();
                                if (response.get("response").getAsInt() == 100) {
                                    correctInput = true;
                                    handler.close();
                                    input.close();
                                    System.out.println("logout done");
                                    return;
                                }
                                else{
                                    System.out.println(response.get("response").getAsInt() + response.get("errorMessage").getAsString());
                                    orderActions();
                                }
                                break;
                            }
                            default:
                                throw new IllegalArgumentException("Invalid input");
                        }
                }catch(IllegalArgumentException e){
                    System.out.println(e.getMessage());
                }
            }
            }
            catch(IOException e){
                System.out.println(e.getMessage());
                System.exit(1);
            }
    }
    public static void Start() {
        System.out.println("Welcome, choose your next action:");
        System.out.println("register <username> <password>");
        System.out.println("login <username> <password>");
        System.out.println("updateCredentials <username> <old_password> <new_password>");

    }
    public static void orderActions(){
        System.out.println("Do your order/action:");
        System.out.println("insertLimitOrder <type (ask/bid)> <size> <prize>");
        System.out.println("insertMarketOrder <type (ask/bid)> <size>");
        System.out.println("insertStopOrder <type (ask/bid)> <size> <price>");
        System.out.println("CancelOrder <orderId>");
        System.out.println("getPriceHistory <month (MMYYYY)>");
        System.out.println("logout <username>"); 
    }
}

//classe per la creazione degli oggetti json da portare in stringa e da inviare al server
class JsonObj{
    private static Gson data = new Gson();
    String operation;
    Map<String, Object> values = new HashMap<>();
    String json;
    Integer UDPport; 
    public String JSONBuilderLogout(Scanner input, String operation){
        this.operation = operation;
        String json = data.toJson(this);
        return json;
    }
    //costruzione json per register, login e updateCredentials
    public String JSONBuilderReglog(Scanner input, String operation, int UDPport){
        this.operation = operation;
        System.out.println("username:");
        String username = input.nextLine();
        this.UDPport = UDPport;
        if(operation.equals("updateCredentials")){
            System.out.println("old_password:");
            String oldPassword = input.nextLine();
            System.out.println("new_password:");
            String newPassword = input.nextLine();
            values.put("username", username);
            values.put("old_password", oldPassword);
            values.put("new_password", newPassword);
            String json = data.toJson(this);
            return json;
        }
        else{
            System.out.println("password:");
            String password = input.nextLine();
            values.put("username", username);
            values.put("password", password);
            String json = data.toJson(this);
            return json;
        }
    }
    //costruzione json insertMarketOrder
    public String JSONBuilderInsertOrders(Scanner input, String operation){
        this.operation = operation;
        Boolean correctInput = false;
        while(!correctInput){
            System.out.println("type:");
            String type = input.nextLine();
            try{
                if (!type.equals("bid") && !type.equals("ask")){
                    throw new IllegalArgumentException("bid and ask are the only accepted inputs");
                }
                values.put("type", type);
                System.out.println("size:");
                int size = Integer.parseInt(input.nextLine());
                values.put("size", size);
                if (operation.equals("insertStopOrder")||operation.equals("insertLimitOrder")){
                    System.out.println("price:");
                    int price = Integer.parseInt(input.nextLine());
                    values.put("price", price);
                }
                correctInput = true;
                json = data.toJson(this);
                return json;
            }
            catch(NumberFormatException e){
                System.out.println("Invalid input, size and price are integers");
            }
            catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
        return "invalid";
    }
    //costruzione json CancelOrder
    public String JSONBuilderCancelOrder(Scanner input, String operation){
        this.operation = operation;
        Boolean correctInput = false;
        while(!correctInput){
            try{
                System.out.println("orderId:");        
                int orderId = Integer.parseInt(input.nextLine());
                values.put("orderId", orderId);
                correctInput = true;
                json = data.toJson(this);
                return json;
            }
            catch(NumberFormatException e){
                System.out.println("Invalid input, size and price are integers");
            }
        }
        return "invalid";
    }
    //costruzione json getPriceHistory
    public String JSONBuilderHistory(Scanner input, String operation){
        this.operation = operation;
        Boolean correctInput = false;
        while(!correctInput){
            try{
                System.out.println("month:");
                String date  = input.nextLine();
                int month = Integer.parseInt(date);
                if (month> 129999 || month<000000){
                    throw new IllegalArgumentException("Invalid month: month>12 or month<0 not accepted");
                }
                values.put("month", date);
                correctInput = true;
                json = data.toJson(this);
                return json;
            }
            catch(NumberFormatException e){
                System.out.println("Invalid input, month is an integer");
            }
            catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
        return "invalid";
    }
}

//classe per la gestione della connessione con il server e quindi per l'invio e la ricezione di stringhe 
class ClientHandler {
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    //metodo per creazione client e canali input output
    public ClientHandler(String serverAddress, int port) throws IOException {
        socket = new Socket(serverAddress, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
    }

    //metodo per invio e ricezione di messaggi TCP
    public String sendAndReceive(String json) throws IOException {
        System.out.println(json);
        out.write(json);
        out.newLine();
        out.flush();
        return in.readLine();
    }

    //metodo per chiudere la connessione
    public void close() throws IOException {
        socket.close();
    }
}

//classe per la gestione delle nontifiche asincrone in entrata dal server
class AsynchronousNotification extends Thread {
    static int UDPport;
    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            UDPport = socket.getLocalPort();
            System.out.println("UDP listening on port " + socket.getLocalPort());
            byte[] buffer = new byte[4096];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                System.out.println("Received UDP: " + msg + " from " + packet.getAddress() + ":" + packet.getPort());
                
                try {
                    JsonObject notification = JsonParser.parseString(msg).getAsJsonObject();
                    String type = notification.get("notification").getAsString();
                    if (type.equals("closedTrades")) {
                        JsonObject trades = notification.get("trades").getAsJsonObject();
                        System.out.println(trades);
                    }
                } catch (Exception e) {
                    System.out.println("JSON parsing error : " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("Error receiving UDP notification: " + e.getMessage());
        }
    }
}





