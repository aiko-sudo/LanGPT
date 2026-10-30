package app.remotessh;

import org.json.JSONObject;

/** Only user choices; never contains server-request approvals or force-grant state. */
final class SessionDefaults {
  final String model, effort;
  final PermissionMode permission;
  SessionDefaults(String model,String effort,PermissionMode permission) {
    this.model=model==null?"":model;this.effort=effort==null?"":effort;
    this.permission=permission==null?PermissionMode.INHERIT:permission;
  }
  String encode(){return Json.obj("model",model,"effort",effort,"permission",permission.name()).toString();}
  static SessionDefaults decode(String encoded) {
    if(encoded==null || encoded.isEmpty())return null;
    try {
      JSONObject object=new JSONObject(encoded);
      PermissionMode mode=PermissionMode.INHERIT;
      try {mode=PermissionMode.valueOf(object.optString("permission",PermissionMode.INHERIT.name()));}
      catch(IllegalArgumentException ignored){}
      return new SessionDefaults(object.optString("model"),object.optString("effort"),mode);
    }catch(Exception ignored){return null;}
  }
  static String key(String profile,String runtime){return profile+":"+runtime;}
}
