# Quarkus Reactive messaging con Kafka

Crearemos una aplicación Quarkus Reactive que utiliza el proyecto SmallRye Reactive Messaging y Mutiny para transmitir datos desde y hacia un clúster Kafka.


# Mensajería reactiva en pocas palabras

El estilo de arquitectura de las aplicaciones empresariales ha ido cambiando en los últimos años. Además del enfoque cliente-servidor estándar, ahora tenemos microservicios, aplicaciones reactivas e incluso aplicaciones sin servidor.

Quarkus es un framework reactivo. Puede entregar aplicaciones reactivas utilizando dos integraciones principales: la especificación Eclipse MicroProfile Reactive y SmallRye Mutiny.

Microprofile Reactive Messaging es una especificación que utiliza beans CDI para dirigir el flujo de mensajes hacia algunos canales específicos.

Puede utilizar las siguientes anotaciones se utilizan para indicar si un método es un productor o un consumidor de mensajes:

org.eclipse.microprofile.reactive.messaging.Incoming - Se utiliza para significar un suscriptor de mensajes entrantes
org.eclipse.microprofile.reactive.messaging.Outgoing - Se utiliza para indicar un editor de mensajes salientes.

Con respecto a las fuentes de eventos, Quarkus utiliza el Mutiny Framework como modelo primario. Con Mutiny, observas eventos, reaccionas a ellos, y creas tuberías de procesamiento elegantes y legibles.

Smallrye Mutiny ofrece dos tipos que son a la vez impulsados por eventos y perezosos:

Un **Uni** emite un único evento (un elemento o un fallo). Los Unis son convenientes para representar acciones asíncronas que devuelven 0 o 1 resultado. Un buen ejemplo es el resultado de enviar un mensaje a la cola de un agente de mensajes.
Un **Multi** emite múltiples eventos (n ítems, 1 fallo o 1 finalización). Los Multi pueden representar flujos de elementos, potencialmente ilimitados. Un buen ejemplo es la recepción de mensajes de una cola de agentes de mensajes.
Como ejemplo de combinación de Microprofile Reactive Messaging con Mutiny considere el siguiente ejemplo:

```mermaid
@Outgoing("source")
public Multi<String>  generate()  {
  return Multi.createFrom().iterable(Arrays.asList("some", "iterable","data"));
}

@Incoming("source")
@Outgoing("destination")
public  String  process(String in)  {
  return in.toUpperCase();
}

@Incoming("destination")
public  void  consume(String processed)  {
  System.out.println(processed);
}
```

Reactive Messaging enlaza automáticamente @Outgoing con @Incoming para formar una cadena. Por lo tanto, se generará la siguiente cadena:

```mermaid
generate -->  [ source ] --> process -->  [ desination ] --> consume
```

## Crear la aplicación Quarkus

En primer lugar, vamos a poner en marcha nuestra aplicación "kafka-demo". Utiliza cualquier herramienta disponible como Quarkus CLI:

```mermaid
quarkus create app kafka-demo
```
A continuación, vaya a la carpeta kafka-demo y añada las siguientes extensiones:

```mermaid
$ quarkus ext add smallrye-reactive-messaging-kafka resteasy-jackson
Looking for the newly published extensions in registry.quarkus.io
[SUCCESS] ✅  Extension io.quarkus:quarkus-smallrye-reactive-messaging-kafka has been installed
[SUCCESS] ✅  Extension io.quarkus:quarkus-resteasy-jackson has been installed
```
Nuestra aplicación transmitirá en tiempo real las cotizaciones de bolsa. La forma más sencilla de hacerlo es incluir la biblioteca de **Yahoo Finance** a su proyecto:

```mermaid
<dependency>
  <groupId>com.yahoofinance-api</groupId>
  <artifactId>YahooFinanceAPI</artifactId>
  <version>3.15.0</version>
</dependency>
```

## Establecer productores y consumidores

Hemos terminado con el esqueleto del proyecto. Lo siguiente, será codificar los CDI Beans para manejar los mensajes Entrantes y Salientes ademas de un REST Endpoint que publicará las cotizaciones como Server Side Events.

En primer lugar, añadiremos un QuoteGenerator. Este CDI Bean producirá cotizaciones de bolsa en el método generate, que se declara como publicado mediante la anotación @Outgoing("stock-quote"):

```mermaid
@ApplicationScoped
public class QuoteGenerator {
  int counter = 0;

  @ConfigProperty(name = "stock.tickers")
  List<String> stocks;

  @Outgoing("stock-quote")
  public Multi<Quote> generate() {
    return   
    Multi.createFrom().ticks()
    .every(Duration.ofSeconds(1)).map(n ->  generateQuote());
  }

  private Quote generateQuote() {
    Stock stock = null;
    String ticker = stocks.get(getCounter());
    
    try {
      stock = YahooFinance.get(ticker);
    } catch  (IOException e) {
      e.printStackTrace();
    }

    BigDecimal price = stock.getQuote().getPrice();
    BigDecimal priceChange = stock.getQuote().getChange();

    Quote q = new  Quote();
    q.setCompany(ticker);
    q.setValue(price.doubleValue());
    q.setChange(priceChange.doubleValue());

    return q;
  }

  private int getCounter() {
    counter++;

    if (counter == stocks.size()) {
      counter = 0;
    }
    return counter;
  }
}
```
En este ejemplo, usamos el método generateQuote para obtener el precio y el cambio de la acción de **YahooFinance**, usando el Ticker de la acción como clave (ej: AAPL significa Apple). El método getCounter es un método de ayuda para rotar sobre la Lista de Acciones.

A continuación, los mensajes Salientes, son consumidos por la siguiente Clase QuoteConverter:

```mermaid
@ApplicationScoped
public  class QuoteConverter {
  DateFormat dateFormat = new SimpleDateFormat("hh:mm:ss");

  @Incoming("stocks")
  @Outgoing("in-memory-stream")
  @Broadcast
  public Quote newQuote(Quote quote)  throws Exception {
    Date date = Calendar.getInstance().getTime();

    String strDate = dateFormat.format(date);
    quote.setTime(strDate);
    return quote;
  }
}
```
En las aplicaciones basadas en Stream, un Conversor realiza cualquier tipo de cambio/filtro en los mensajes. En nuestro ejemplo, no está haciendo nada complicado: sólo establece la marca de tiempo de la Cita actual.

Como puedes ver, los mensajes están ahora en el canal "in-memory-stream". La última parada será el REST **Endpoint** que consume de este canal y publica los datos como **Server Side Events**:

```mermaid
@Path("/quotes")
public class QuoteEndpoint {
	@Channel("in-memory-stream")
	Publisher<Quote> quotes;

	@ConfigProperty(name = "stock.tickers")
	List<String> stocks;

	@GET
	@Path("/stream")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	@SseElementType(MediaType.APPLICATION_JSON)

	public Publisher<Quote> stream() {
		return quotes;
	}

	@GET
	@Path("/init")
    public List<Quote> getList()  {
	     List<Quote> list = new  ArrayList();

		for (String ticker : stocks) {
			list.add(new  Quote(ticker));
		}
		return list;
	}

}
```
- El método stream publica datos (en formato JSON) como Server Side Events
- El método getList publica la lista de Stocks como elementos de Arrays JSON. El único propósito de este método es crear la rejilla front-end dinámicamente en el arranque.

Nuestra aplicación está casi lista. La clase básica Quote completa el código Java:

```mermaid
public  class Quote {
	String company;
	Double value;
	Double change;
	String time;
	// Getters y Setters son necesarios 
}
```

## Configuración de la aplicación

El archivo **application.properties** contiene algunas cosas importantes:

```mermaid
#Some Nasdaq Stocks
stock.tickers=AAPL,MSFT,AMZN,GOOG,TSLA,NVDA,FB,AVGO,COST,CSCO

# Kafka destination
mp.messaging.outgoing.stock-quote.connector=smallrye-kafka
mp.messaging.outgoing.stock-quote.topic=stocks

# Kafka source (Leemos de aqui)
mp.messaging.incoming.stocks.connector=smallrye-kafka
mp.messaging.incoming.stocks.topic=stocks

quarkus.reactive-messaging.kafka.serializer-generation.enabled=true
```
A continuación, para desacoplar los mensajes de productores y consumidores, utilizamos los conectores Kafka de SmallRye Kafka. Los mensajes de productores y consumidores se dividen en dos bloques:

- En el primer bloque, configuramos el topic y el conector de destino de Kafka, donde enviamos los mensajes como Stock Quote en formato JSON.

- En el segundo bloque, configuramos el topic y conector de origen de Kafka, donde leemos los mensajes como Stock Quote en Formato JSON...

Por último, (aunque por defecto es true) habilitamos la serialización automática de los datos que, por defecto se envían como Bytes.

Esta imagen resume todo el esquema de mensajes desde / hacia Apache Kafka:

![quarkus kafka tutorial](http://www.mastertheboss.com/wp-content/uploads/2022/03/kafka-quarkus1.png)

## La capa de front-end

Finalmente, para completar nuestra aplicación, todo lo que necesitamos hacer es añadir una aplicación cliente que sea capaz de capturar la SSE y mostrar
el texto en un formato legible. En aras de la brevedad, vamos a incluir aquí sólo el núcleo de la función JavaScript que utiliza Javascript y jQuery para suscribirse a los eventos SSE:

```mermaid
<!DOCTYPE html>  
<html lang="en">  
<head>
<meta charset="UTF-8"><title>Quarkus Demo - Kafka</title>  
<style>    
  div.blueTable {  
  border: 1px solid #1C6EA4;  
  background-color: #EEEEEE;  
  width: 50%;  
  text-align: left;  
  border-collapse: collapse;  
}  
.divTable.blueTable .divTableCell, .divTable.blueTable .divTableHead {  
  border: 1px solid #AAAAAA;  
  padding: 3px 2px;  
}  
.divTable.blueTable .divTableBody .divTableCell {  
  font-size: 13px;  
}  
.divTable.blueTable .divTableRow:nth-child(even) {  
  background: #D0E4F5;  
}  
.divTable.blueTable .divTableHeading {  
  background: #1C6EA4;  
  background: -moz-linear-gradient(top, #5592bb 0%, #327cad 66%, #1C6EA4 100%);  
  background: -webkit-linear-gradient(top, #5592bb 0%, #327cad 66%, #1C6EA4 100%);  
  background: linear-gradient(to bottom, #5592bb 0%, #327cad 66%, #1C6EA4 100%);  
  border-bottom: 2px solid #444444;  
}  
.divTable.blueTable .divTableHeading .divTableHead {  
  font-size: 15px;  
  font-weight: bold;  
  color: #FFFFFF;  
  border-left: 2px solid #D0E4F5;  
}  
.divTable.blueTable .divTableHeading .divTableHead:first-child {  
  border-left: none;  
}  
  
.blueTable .tableFootStyle {  
  font-size: 14px;  
}  
.blueTable .tableFootStyle .links {  
    text-align: right;  
}  
.blueTable .tableFootStyle .links a{  
  display: inline-block;  
  background: #1C6EA4;  
  color: #FFFFFF;  
  padding: 2px 8px;  
  border-radius: 5px;  
}  
.blueTable.outerTableFooter {  
  border-top: none;  
}  
.blueTable.outerTableFooter .tableFootStyle {  
  padding: 3px 5px;  
}  
/* DivTable.com */  
.divTable{ display: table; }  
.divTableRow { display: table-row; }  
.divTableHeading { display: table-header-group;}  
.divTableCell, .divTableHead { display: table-cell;}  
.divTableHeading { display: table-header-group;}  
.divTableFoot { display: table-footer-group;}  
.divTableBody { display: table-row-group;}  
</style>  
</head>  
<body>  
<input type="button" id = "btnGenerate" value="Connect to Stock Exchange" />    
<div class="container">  
 <h2>Quarkus Demo - Kafka messaging</h2>  
 <h3>Stock Quotes</h3>  
 <div class="divTable blueTable">  
 <div class="divTableHeading">  
 <div class="divTableRow">  
 <div class="divTableHead">Company</div>  
 <div class="divTableHead">Stock Quote</div>  
 <div class="divTableHead">Change</div>  
 </div> </div> <div id="dvTable" class="divTableBody"/>  
 </div> <div class="divTableBody">Last update:<span id="timestamp">NA</span></div>  
</div>  
</body>  
<script src="https://code.jquery.com/jquery-3.3.1.min.js"></script>  
<script>  
  var source = new EventSource("/quotes/stream");  
   source.onmessage = function (event) {  
  
    var data = JSON.parse(event.data);  
    var company = data['company'];  
    var value = data['value'];  
    var change = data['change'];  
    var timestamp = data['time'];  
    var companyChange = company+"-change";  
    if ( document.getElementById(company) != null){  
        document.getElementById(company).innerHTML = value;  
        document.getElementById(companyChange).innerHTML = change;  
        if (change < 0) {  
       document.getElementById(companyChange).style.color="red";  
        }  
        else {  
     document.getElementById(companyChange).style.color="green";  
        }  
        document.getElementById("timestamp").innerHTML = timestamp;  
   }  
  }  
</script>  
<script type="text/javascript">  
$(function () {  
    $("#btnGenerate").click(function () {  
    (function() {  
  var myAPI = "http://localhost:8080/quotes/init";  
  $.getJSON(myAPI, {  
      format: "json"  
    })  
    .done(function(data) {  
      printTable(data);  
    });  
})();  
  
function printTable(response) {  
   var trHTML = '';  
        $.each(response, function (i, item) {  
            var companyChange = item.company+'-change';  
            trHTML += '<div class="divTableRow">';  
            trHTML += '<div class="divTableCell">' + item.company + '</div>'  
            trHTML += '<div class="divTableCell"><span id="'+ item.company +'">' + item.value + '</span></div>';  
            trHTML += '<div class="divTableCell"><span id="'+ companyChange +'">' + item.change + '</span></div>';  
            trHTML += '</div>';  
        });  
        $('#dvTable').append(trHTML);  
         }  
    });  
});  
</script>  
</html>
```

## Cómo ejecutar la aplicación

Antes de iniciar la aplicación Quarkus, necesitamos un clúster de **Kafka y Zookeeper** en funcionamiento. La solución más rápida es iniciarlos como contenedores Docker a través de la herramienta **docker-compose**.

El siguiente archivo **docker-compose.yaml** inicia un clúster de servidor **Zookeeper** y **Apache Kafka** utilizando el proyecto OpenSource [Apache Strimzi](https://strimzi.io/):

```mermaid
version:  '3.5'

services:
zookeeper:
image: quay.io/strimzi/kafka:0.23.0-kafka-2.8.0
command:  [
"sh", "-c",
"bin/zookeeper-server-start.sh config/zookeeper.properties"
]
ports:
- "2181:2181"
environment:
LOG_DIR: /tmp/logs
kafka:
image: quay.io/strimzi/kafka:0.23.0-kafka-2.8.0
command:  [
"sh", "-c",
"bin/kafka-server-start.sh config/server.properties --override listeners=$${KAFKA_LISTENERS} --override advertised.listeners=$${KAFKA_ADVERTISED_LISTENERS} --override zookeeper.connect=$${KAFKA_ZOOKEEPER_CONNECT}"
]
depends_on:
- zookeeper
ports:
- "9092:9092"
environment:
LOG_DIR:  "/tmp/logs"
KAFKA_ADVERTISED_LISTENERS:  PLAINTEXT://kafka:9092
KAFKA_LISTENERS:  PLAINTEXT://0.0.0.0:9092
KAFKA_ZOOKEEPER_CONNECT:  zookeeper:2181
```

Ahora también puede construir e iniciar la aplicación Quarkus:

```mermaid
mvn install quarkus:dev
```
La aplicación está disponible en localhost:8080

![quarkus kafka tutorial](http://www.mastertheboss.com/wp-content/uploads/2022/03/quarkus-kafka.png)
