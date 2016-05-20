package com.example.unzi.findalert.ui;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.example.unzi.findalert.R;

public class Main2Activity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        RegisterInFind rif = RegisterInFind.sharedInstance(this);
        rif.register();

    }
}
