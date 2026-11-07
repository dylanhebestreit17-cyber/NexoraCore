package net.nexora.core;

import java.math.BigDecimal;
import java.util.Locale;

/** Prices are exact whole coins or euro cents; never floating point. */
public record ShopProduct(String id,String name,long price,String currency,String description,String category,
                          boolean active,String kind,String reward,long revision,boolean deleted) {
    public static String validId(String id){
        if(id==null || !id.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("ID: 1–32 Kleinbuchstaben, Ziffern, _ oder -");return id;
    }
    public static String text(String value,int max){
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Text fehlt, ist zu lang oder enthält Steuerzeichen");return value;
    }
    public static String currency(String value){
        if(value==null)throw new IllegalArgumentException("Währung fehlt");
        String result=value.toLowerCase(Locale.ROOT);
        if(!result.equals("coins") && !result.equals("eur"))throw new IllegalArgumentException("Währung muss coins oder eur sein");return result;
    }
    public static long price(String value,String currency){
        currency(currency);
        if(value==null || !value.matches("[0-9]{1,19}([.,][0-9]{1,2})?"))throw new IllegalArgumentException("Nichtnegativer exakter Preis erwartet");
        if(currency.equals("coins") && (value.contains(".") || value.contains(",")))throw new IllegalArgumentException("Coin-Preise müssen ganzzahlig sein");
        try{return new BigDecimal(value.replace(',','.')).movePointRight(currency.equals("eur")?2:0).longValueExact();}
        catch(ArithmeticException e){throw new IllegalArgumentException("Preis zu groß");}
    }
    public String displayPrice(){return currency.equals("eur")?BigDecimal.valueOf(price,2).toPlainString()+" EUR":price+" Coins";}
    public static String kind(String value){
        if(!java.util.Set.of("item","kit","rank","coins","cosmetic","custom").contains(value))throw new IllegalArgumentException("Unbekannte Produktart");return value;
    }
}
